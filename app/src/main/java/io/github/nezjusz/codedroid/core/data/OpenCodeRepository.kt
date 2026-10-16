package io.github.nezjusz.codedroid.core.data

import android.util.Log
import io.github.nezjusz.codedroid.core.net.AgentInfo
import io.github.nezjusz.codedroid.core.net.ModelInfo
import io.github.nezjusz.codedroid.core.net.ModelRef
import io.github.nezjusz.codedroid.core.net.OcEvent
import io.github.nezjusz.codedroid.core.net.OpenCodeClient
import io.github.nezjusz.codedroid.core.net.OpenCodeException
import io.github.nezjusz.codedroid.core.net.PermissionRequest
import io.github.nezjusz.codedroid.core.net.Project
import io.github.nezjusz.codedroid.core.net.ServerInfo
import io.github.nezjusz.codedroid.core.net.SessionInfo
import io.github.nezjusz.codedroid.core.notify.Notifier
import io.github.nezjusz.codedroid.core.net.FileAttachment
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive

enum class ConnectionState { Idle, Connecting, Live, Offline }

data class ServerRuntime(
    val serverId: String,
    val state: ConnectionState = ConnectionState.Idle,
    val info: ServerInfo? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val projects: List<Project> = emptyList(),
    val permissions: Map<String, PermissionRequest> = emptyMap(),
    val busySessions: Set<String> = emptySet(),
    val lastError: String? = null,
    val lastSyncAt: Long = 0,
) {
    val live: Boolean get() = state == ConnectionState.Live

    val sessionsAwaitingApproval: List<SessionInfo>
        get() = sessions.filter { permissions.values.any { p -> p.sessionID == it.id } }

    val sessionsBusy: List<SessionInfo>
        get() = sessions.filter { it.id in busySessions }

    /** Number of permission requests currently blocking an agent. */
    val attention: Int get() = permissions.size
}

/**
 * Single source of truth for every connected host.
 *
 * Each monitored server owns a long-lived SSE connection plus a low-frequency reconcile poll.
 * SSE alone is not enough: it is live-only with no replay, so anything that happened while the
 * phone was offline is picked up by [refreshSessions].
 */
class OpenCodeRepository(
    private val store: ServerStore,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _servers = MutableStateFlow<List<SavedServer>>(emptyList())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    private val _runtimes = MutableStateFlow<Map<String, ServerRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<String, ServerRuntime>> = _runtimes.asStateFlow()

    private val clients = ConcurrentHashMap<String, OpenCodeClient>()
    private val monitors = ConcurrentHashMap<String, Job>()
    private val transcripts = ConcurrentHashMap<String, TranscriptHandle>()

    init {
        scope.launch {
            store.servers.collect { list ->
                _servers.value = list
                val ids = list.map { it.id }.toSet()
                (monitors.keys - ids).forEach { stopMonitoring(it) }
                val current = _runtimes.value
                _runtimes.value = list.associate { s ->
                    s.id to (current[s.id] ?: ServerRuntime(s.id))
                }
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    fun server(serverId: String): SavedServer? = _servers.value.firstOrNull { it.id == serverId }

    fun client(serverId: String): OpenCodeClient {
        val existing = clients[serverId]
        if (existing != null) return existing
        val server = requireNotNull(server(serverId)) { "Unknown server $serverId" }
        return clients.computeIfAbsent(serverId) { OpenCodeClient(server.baseUrl, server.token) }
    }

    private fun mutate(serverId: String, block: (ServerRuntime) -> ServerRuntime) {
        _runtimes.value = _runtimes.value.toMutableMap().apply {
            val current = get(serverId) ?: ServerRuntime(serverId)
            this[serverId] = block(current)
        }
    }

    // ------------------------------------------------------------------ monitoring

    /** Starts (or restarts) the background connection for one host. */
    fun startMonitoring(serverId: String) {
        if (monitors[serverId]?.isActive == true) return
        monitors[serverId] = scope.launch {
            mutate(serverId) { it.copy(state = ConnectionState.Connecting, lastError = null) }
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                val server = server(serverId)
                if (server == null) break
                try {
                    val client = client(serverId)
                    mutate(serverId) { it.copy(state = ConnectionState.Connecting) }
                    reconcile(serverId, client, server)
                    backoff = INITIAL_BACKOFF_MS

                    // Consume the stream until it breaks; then back off and retry.
                    client.events().collect { event ->
                        mutate(serverId) { it.copy(state = ConnectionState.Live, lastError = null, lastSyncAt = System.currentTimeMillis()) }
                        handleEvent(serverId, event)
                    }
                    mutate(serverId) { it.copy(state = ConnectionState.Offline) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = describe(e)
                    Log.w(TAG, "monitor $serverId failed: $message")
                    mutate(serverId) { it.copy(state = ConnectionState.Offline, lastError = message) }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun stopMonitoring(serverId: String) {
        monitors.remove(serverId)?.cancel()
        _runtimes.value = _runtimes.value - serverId
    }

    /** Starts monitoring every host that asked to stay connected. */
    fun startEnabledServers() {
        scope.launch {
            store.servers.first().forEach { if (it.keepAlive) startMonitoring(it.id) }
        }
    }

    fun addServer(server: SavedServer) {
        scope.launch {
            store.add(server)
            clients.remove(server.id)
            startMonitoring(server.id)
        }
    }

    fun updateServer(server: SavedServer) {
        scope.launch {
            store.update(server)
            clients.remove(server.id)
            stopMonitoring(server.id)
            startMonitoring(server.id)
        }
    }

    fun removeServer(serverId: String) {
        scope.launch {
            store.remove(serverId)
            clients.remove(serverId)
            stopMonitoring(serverId)
        }
    }

    suspend fun pair(baseUrl: String, code: String): SavedServer {
        val token = OpenCodeClient(baseUrl, token = "").redeemPairingCode(code)
        val info = OpenCodeClient(baseUrl, token).info()
        val normalized = OpenCodeClient.normalizeBaseUrl(baseUrl)
        val existing = _servers.value.firstOrNull { it.baseUrl == normalized }
        val server = (existing ?: SavedServer(baseUrl = normalized)).copy(
            name = existing?.name ?: info.urls.firstOrNull()?.let { hostOf(normalized) } ?: hostOf(normalized),
            baseUrl = normalized,
            token = token,
        )
        if (existing != null) store.update(server) else store.add(server)
        clients.remove(server.id)
        startMonitoring(server.id)
        return server
    }

    private fun hostOf(url: String) = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun describe(e: Exception): String = when (e) {
        is OpenCodeException -> when {
            e.unauthorized -> "Authentication rejected — re-pair this host"
            else -> "HTTP ${e.status}: ${e.message}"
        }

        is java.net.ConnectException -> "Connection refused — is `opencode serve` running and reachable?"
        is java.net.SocketTimeoutException -> "Connection timed out"
        is java.net.UnknownHostException -> "Host not found: ${e.message}"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }

    // ------------------------------------------------------------------ reconcile

    private suspend fun reconcile(serverId: String, client: OpenCodeClient, server: SavedServer) {
        val info = client.info()
        val projects = runCatching { client.projects() }.getOrDefault(emptyList())
        // Deliberately unscoped. `GET /api/session` returns sessions across every location the
        // server can see, and `server.directory` is the default for *new* chats, not a history
        // filter — filtering by it here silently hides the rest of the user's sessions.
        val fetched = runCatching { client.sessions(limit = 80) }
            .onFailure { Log.w(TAG, "session list failed: ${it.message}") }
            .getOrNull()
        val permissions = runCatching { client.pendingPermissions(server.directory) }
            .onFailure { Log.w(TAG, "permission sweep failed for ${server.directory}: ${it.message}") }
            .getOrNull()
        val agents = runCatching { client.agents(server.directory) }
            .onFailure { Log.w(TAG, "agent list failed: ${it.message}") }
            .getOrNull()
        val models = runCatching { client.models(server.directory) }
            .onFailure { Log.w(TAG, "model list failed: ${it.message}") }
            .getOrNull()

        metadata[serverId] = HostMetadata(
            info = info,
            projects = projects ?: metadata[serverId]?.projects.orEmpty(),
            agents = agents ?: metadata[serverId]?.agents.orEmpty(),
            models = models ?: metadata[serverId]?.models.orEmpty(),
        )

        mutate(serverId) { current ->
            val known = current.sessions.associateBy { it.id }
            current.copy(
                state = ConnectionState.Live,
                info = info,
                projects = projects ?: current.projects,
                // Merged, and only applied on success: a failed fetch must never replace a
                // known-good list with an empty one, which reads as "history was deleted".
                sessions = fetched?.map { s -> known[s.id]?.let { merge(it, s) } ?: s } ?: current.sessions,
                permissions = permissions?.associate { it.id to it } ?: current.permissions,
                lastError = null,
                lastSyncAt = System.currentTimeMillis(),
            )
        }
        permissions?.let { announcePending(serverId, server.displayName, it) }
    }

    private val metadata = ConcurrentHashMap<String, HostMetadata>()

    /** Permission ids already surfaced, so reconnects do not re-notify for the same blocker. */
    private val announced = ConcurrentHashMap<String, MutableSet<String>>()

    data class HostMetadata(
        val info: ServerInfo? = null,
        val projects: List<Project> = emptyList(),
        val agents: List<AgentInfo> = emptyList(),
        val models: List<ModelInfo> = emptyList(),
    )

    fun metadata(serverId: String): HostMetadata = metadata[serverId] ?: HostMetadata()

    /** One-shot reconnect + reconcile, used by pull-to-refresh. */
    suspend fun refresh(serverId: String): Result<Unit> = runCatching {
        val server = server(serverId) ?: error("Unknown host")
        reconcile(serverId, client(serverId), server)
    }

    suspend fun refreshSessions(serverId: String) {
        runCatching { client(serverId).sessions(limit = 80) }
            .onSuccess { sessions ->
                mutate(serverId) { current ->
                    val known = current.sessions.associateBy { it.id }
                    current.copy(sessions = sessions.map { s -> known[s.id]?.let { merge(it, s) } ?: s })
                }
            }
    }

    /**
     * Session events are frequently partial: a `session.created` payload carries no cost or
     * token counters, and `session.updated` may omit the title. Absent fields keep their
     * previous value instead of resetting to zero or blank.
     */
    private fun merge(old: SessionInfo, fresh: SessionInfo) = fresh.copy(
        title = fresh.title?.takeIf { it.isNotBlank() } ?: old.title,
        agent = fresh.agent ?: old.agent,
        model = fresh.model ?: old.model,
        outcome = fresh.outcome ?: old.outcome,
        location = fresh.location ?: old.location,
        tokens = fresh.tokens ?: old.tokens,
        cost = if (fresh.cost != 0.0) fresh.cost else old.cost,
        time = if (fresh.time.updated != 0L) fresh.time else old.time,
    )

    // ------------------------------------------------------------------ events

    private suspend fun handleEvent(serverId: String, event: OcEvent) {
        val server = server(serverId) ?: return
        when (event) {
            is OcEvent.SessionUpsert -> {
                mutate(serverId) { current ->
                    val index = current.sessions.indexOfFirst { it.id == event.session.id }
                    val sessions = current.sessions.toMutableList()
                    if (index >= 0) sessions[index] = merge(sessions[index], event.session)
                    else sessions.add(0, event.session)
                    current.copy(sessions = sessions)
                }
                transcripts["$serverId/${event.session.id}"]?.refresh()
            }

            is OcEvent.SessionDeleted -> mutate(serverId) {
                it.copy(sessions = it.sessions.filterNot { s -> s.id == event.sessionID })
            }

            is OcEvent.TextDelta -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.TextEnded -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.ReasoningDelta -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.ToolCalled -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.ToolProgress -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.ToolSettled -> liveTranscript(serverId, event.sessionID)?.apply(event)

            is OcEvent.Execution -> {
                mutate(serverId) { current ->
                    val busy = current.busySessions.toMutableSet()
                    when (event.phase) {
                        "started" -> busy.add(event.sessionID)
                        else -> busy.remove(event.sessionID)
                    }
                    current.copy(busySessions = busy)
                }
                if (event.phase != "started") {
                    liveTranscript(serverId, event.sessionID)?.markFinished(
                        when (event.phase) {
                            "succeeded" -> "stop"
                            "failed" -> "error"
                            else -> "interrupt"
                        }
                    )
                    refreshSessions(serverId)
                    if (event.phase == "succeeded" || event.phase == "failed") {
                        if (server.notifyCompletion) {
                            val title = _runtimes.value[serverId]?.sessions
                                ?.firstOrNull { it.id == event.sessionID }?.title ?: "Session"
                            notifier.sessionFinished(serverId, server.displayName, event.sessionID, title, event.phase)
                        }
                    }
                }
            }

            is OcEvent.PermissionAsked -> {
                mutate(serverId) {
                    it.copy(permissions = it.permissions + (event.request.id to event.request))
                }
                announced.getOrPut(serverId) { ConcurrentHashMap.newKeySet() }.add(event.request.id)
                if (server.notifyPermissions) {
                    notifier.permissionRequest(serverId, server.displayName, event.request)
                }
                refreshSessions(serverId)
            }

            is OcEvent.PermissionSettled -> mutate(serverId) {
                it.copy(permissions = it.permissions - event.requestID)
            }

            is OcEvent.ProjectUpsert -> mutate(serverId) { current ->
                val projects = current.projects.toMutableList()
                val index = projects.indexOfFirst { it.id == event.project.id }
                if (index >= 0) projects[index] = event.project else projects.add(event.project)
                current.copy(projects = projects)
            }

            is OcEvent.Usage -> mutate(serverId) { current ->
                current.copy(
                    sessions = current.sessions.map { s ->
                        if (s.id == event.sessionID) {
                            s.copy(cost = event.cost, tokens = event.tokens)
                        } else s
                    }
                )
            }

            OcEvent.ServerConnected -> mutate(serverId) {
                it.copy(state = ConnectionState.Live, lastError = null)
            }
        }
    }

    /** Notifies for blockers discovered by reconcile that were not already announced. */
    private fun announcePending(serverId: String, serverName: String, permissions: List<PermissionRequest>) {
        if (server(serverId)?.notifyPermissions != true) return
        val seen = announced.getOrPut(serverId) { ConcurrentHashMap.newKeySet() }
        val fresh = permissions.filter { seen.add(it.id) }
        if (seen.size > 200) seen.clear()
        fresh.forEach { notifier.permissionRequest(serverId, serverName, it) }
    }

    // ------------------------------------------------------------------ transcripts

    /** Returns the transcript handle for a session, starting a load if needed. */
    fun transcript(serverId: String, sessionID: String): TranscriptHandle =
        transcripts.getOrPut("$serverId/$sessionID") {
            TranscriptHandle(this, scope, serverId, sessionID)
        }

    private fun liveTranscript(serverId: String, sessionID: String): TranscriptHandle? =
        transcripts["$serverId/$sessionID"]

    fun releaseTranscript(serverId: String, sessionID: String) {
        transcripts.remove("$serverId/$sessionID")
    }

    // ------------------------------------------------------------------ actions

    suspend fun sendPrompt(
        serverId: String,
        sessionID: String,
        text: String,
        delivery: String?,
        agent: String?,
        files: List<FileAttachment> = emptyList(),
    ): Result<Unit> = runCatching {
        val client = client(serverId)
        val localId = "${if (delivery == "queue") "queued" else "local"}_${System.nanoTime()}"
        val shown = buildString {
            append(text)
            if (files.isNotEmpty()) {
                append('\n')
                files.forEach { file ->
                    append("\n📎 ").append(file.name ?: file.uri)
                }
            }
        }
        liveTranscript(serverId, sessionID)?.addOptimisticUser(
            text = shown,
            id = localId,
            time = System.currentTimeMillis(),
        )
        client.prompt(sessionID, text, delivery = delivery, agent = agent, files = files)
    }

    suspend fun interrupt(serverId: String, sessionID: String): Boolean =
        runCatching { client(serverId).interrupt(sessionID) }.getOrDefault(false)

    suspend fun replyPermission(serverId: String, sessionID: String, requestID: String, reply: String): Boolean =
        runCatching {
            client(serverId).replyPermission(sessionID, requestID, reply)
            mutate(serverId) { it.copy(permissions = it.permissions - requestID) }
            true
        }.getOrDefault(false)

    suspend fun createSession(serverId: String, directory: String, title: String? = null): Result<SessionInfo> =
        runCatching { client(serverId).createSession(directory, title) }

    suspend fun deleteSession(serverId: String, sessionID: String): Result<Unit> =
        runCatching { client(serverId).deleteSession(sessionID) }

    suspend fun agents(serverId: String, directory: String?): List<AgentInfo> =
        runCatching { client(serverId).agents(directory) }.getOrDefault(emptyList())

    suspend fun models(serverId: String, directory: String?): List<ModelInfo> =
        runCatching { client(serverId).models(directory) }.getOrDefault(emptyList())

    suspend fun setServerDirectory(serverId: String, directory: String) {
        server(serverId)?.let { updateServer(it.copy(directory = directory)) }
    }

    /**
     * Where uploads land.
     *
     * Prefers the server's own tmp directory, which keeps attachments out of the project tree where
     * the agent could pick them up in a glob or commit them. Falls back to the session directory
     * if the host did not advertise a tmp path.
     */
    suspend fun attachmentDirectory(serverId: String): String {
        val advertised = metadata(serverId).info?.paths?.get("tmp")
            ?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() }
        if (advertised != null) return advertised
        return server(serverId)?.directory
            ?: _runtimes.value[serverId]?.sessions?.firstOrNull()?.directory
            ?: ""
    }

    /**
     * Uploads one attachment, reporting progress as it goes.
     *
     * The host path is namespaced by a timestamp and the original name so two uploads of the same
     * file do not collide, and so the agent can see what it was given.
     */
    /** Uploads one attachment, returning the absolute host path it was written to. */
    suspend fun uploadAttachment(
        serverId: String,
        attachment: Attachment,
        onProgress: (AttachmentState) -> Unit,
    ): Result<String> = runCatching {
        val targetDir = attachmentDirectory(serverId)
        val safeName = attachment.sanitiseFileName(attachment.displayName)
        val stamp = System.currentTimeMillis()
        val target = "$targetDir/codedroid-$stamp-$safeName"

        // Progress arrives on OkHttp's writer thread; the callback drives Compose state, so hop
        // to main before touching it.
        val emit: (AttachmentState) -> Unit = { state ->
            mainScope.launch { onProgress(state) }
        }

        client(serverId).uploadFile(
            directory = targetDir,
            path = target,
            content = attachment.content,
        ) { written, total ->
            emit(AttachmentState.Uploading(written, total))
        }.also { hostPath ->
            emit(AttachmentState.Uploaded(hostPath))
        }
    }.onFailure { error ->
        mainScope.launch { onProgress(AttachmentState.Failed(error.message ?: "Upload failed")) }
    }

    /** Outcome of an upload batch. */
    data class UploadBatch(
        val attachments: List<FileAttachment>,
        val failed: List<Pair<Attachment, String>>,
    ) {
        val allSucceeded: Boolean get() = failed.isEmpty()
    }

    /**
     * Uploads every attachment concurrently.
     *
     * Success is decided from each [Result], not from the attachment's mutable state: state is
     * published asynchronously to the UI, so reading it here would race the main thread.
     */
    suspend fun uploadAll(
        serverId: String,
        attachments: List<Attachment>,
        onProgress: (String, AttachmentState) -> Unit,
    ): UploadBatch = coroutineScope {
        val outcomes: List<Pair<Attachment, Result<String>>> = attachments.map { attachment ->
            async {
                attachment to uploadAttachment(serverId, attachment) { state ->
                    onProgress(attachment.id, state)
                }
            }
        }.awaitAll()

        val succeeded = mutableListOf<FileAttachment>()
        val failed = mutableListOf<Pair<Attachment, String>>()

        outcomes.forEach { (attachment, result) ->
            result.fold(
                onSuccess = { hostPath ->
                    succeeded += FileAttachment(
                        uri = "file://$hostPath",
                        name = attachment.displayName,
                        description = "Attached from the CodeDroid app (${attachment.readableSize})",
                    )
                },
                onFailure = { error ->
                    failed += attachment to (error.message ?: "Upload failed")
                },
            )
        }

        UploadBatch(attachments = succeeded, failed = failed)
    }

    /**
     * Writes a new approval mode to the host.
     *
     * Applies to this session only, not the agent or the project, so widening it is never a
     * standing change you inherit in the next chat.
     */
    suspend fun setApprovalMode(
        serverId: String,
        sessionID: String,
        mode: ApprovalMode,
    ): Result<Unit> = runCatching {
        val updated = client(serverId).setPermissions(sessionID, mode.rules)
        mutate(serverId) { current ->
            current.copy(
                sessions = current.sessions.map { if (it.id == sessionID) updated else it }
            )
        }
    }

    companion object {
        private const val TAG = "OpenCodeRepo"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/** Observable transcript for one open session. */
class TranscriptHandle(
    private val repo: OpenCodeRepository,
    private val scope: CoroutineScope,
    private val serverId: String,
    val sessionID: String,
) {
    private val transcript = Transcript()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = loading.asStateFlow()

    private val loadMutex = Mutex()

    suspend fun load() {
        if (!loadMutex.tryLock()) return
        try {
            loading.value = true
            val messages = repo.client(serverId).messages(sessionID)
            transcript.seed(sessionID, messages)
            emit(transcript.ordered)
        } catch (e: Exception) {
            Log.w("Transcript", "load failed for $sessionID: ${e.message}")
        } finally {
            loading.value = false
            loadMutex.unlock()
        }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun apply(event: OcEvent): Boolean {
        if (!transcript.apply(event)) return false
        emit(transcript.ordered)
        return true
    }

    /** Called when `session.execution.*` reports that the turn is over. */
    fun markFinished(reason: String = "stop") {
        if (transcript.markExecutionEnded(reason)) emit(transcript.ordered)
    }

    fun addOptimisticUser(text: String, id: String, time: Long) {
        transcript.addOptimisticUser(text, id, time)
        emit(transcript.ordered)
    }

    private fun emit(entries: List<Entry>) {
        _entries.value = entries
    }
}
