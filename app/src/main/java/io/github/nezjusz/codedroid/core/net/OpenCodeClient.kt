package io.github.nezjusz.codedroid.core.net

import io.github.nezjusz.codedroid.core.net.OcEvent.Execution
import io.github.nezjusz.codedroid.core.net.OcEvent.PermissionAsked
import io.github.nezjusz.codedroid.core.net.OcEvent.PermissionSettled
import io.github.nezjusz.codedroid.core.net.OcEvent.ProjectUpsert
import io.github.nezjusz.codedroid.core.net.OcEvent.ReasoningDelta
import io.github.nezjusz.codedroid.core.net.OcEvent.SessionDeleted
import io.github.nezjusz.codedroid.core.net.OcEvent.SessionUpsert
import io.github.nezjusz.codedroid.core.net.OcEvent.TextDelta
import io.github.nezjusz.codedroid.core.net.OcEvent.TextEnded
import io.github.nezjusz.codedroid.core.net.OcEvent.ToolCalled
import io.github.nezjusz.codedroid.core.net.OcEvent.ToolProgress
import io.github.nezjusz.codedroid.core.net.OcEvent.ToolSettled
import io.github.nezjusz.codedroid.core.net.OcEvent.Usage
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** A failure talking to an opencode server. */
class OpenCodeException(
    val status: Int,
    override val message: String,
    val tag: String? = null,
) : IOException(message) {
    val unauthorized: Boolean get() = status == 401
}

val OpenCodeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Thin client over the opencode v2 HTTP API.
 *
 * Authentication is HTTP Basic with the fixed user `opencode` and the server password / pairing
 * token as the password. Every instance of this class targets exactly one server.
 */
class OpenCodeClient(
    baseUrl: String,
    private val token: String,
    httpClient: OkHttpClient? = null,
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val authHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("opencode:$token".toByteArray())

    private fun urlFor(path: String, params: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = (this.baseUrl + path).toHttpUrl().newBuilder()
        for ((k, v) in params) {
            if (!v.isNullOrEmpty()) builder.addQueryParameter(k, v)
        }
        return builder.build()
    }

    private fun newRequest(path: String, params: Map<String, String?> = emptyMap()): Request.Builder =
        Request.Builder()
            .url(urlFor(path, params))
            .header("Authorization", authHeader)
            .header("Accept", "application/json")

    // ----------------------------------------------------------------- requests

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = http.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw it.toError(body)
            body
        }
    }

    private suspend fun get(path: String, params: Map<String, String?> = emptyMap()): String =
        execute(newRequest(path, params).get().build())

    private suspend fun post(path: String, body: JsonObject? = null): String =
        execute(
            newRequest(path)
                .post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
                .build()
        )

    private suspend fun patch(path: String, body: JsonObject): String =
        execute(newRequest(path).patch(body.toString().toRequestBody(jsonMedia)).build())

    private suspend fun delete(path: String): String =
        execute(newRequest(path).delete().build())

    private fun Response.toError(body: String): OpenCodeException {
        val tag = runCatching {
            OpenCodeJson.parseToJsonElement(body).let { it as? JsonObject }
                ?.get("_tag")?.let { k -> (k as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        val message = runCatching {
            OpenCodeJson.parseToJsonElement(body).let { it as? JsonObject }
                ?.get("message")?.let { m -> (m as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull() ?: "HTTP $code"
        return OpenCodeException(code, message, tag)
    }

    private inline fun <reified T> decode(body: String): T =
        OpenCodeJson.decodeFromString(body)

    // ----------------------------------------------------------------- endpoints

    /** Cheap reachability + identity probe. */
    suspend fun info(): ServerInfo = decode(get("/api/info"))

    /**
     * Redeem a pairing code for a long-lived token.
     *
     * This endpoint is unauthenticated by design; it is only reachable with a fresh,
     * single-use code generated on the host via `opencode api post /api/pair`.
     */
    suspend fun redeemPairingCode(code: String): String {
        val request = Request.Builder()
            .url(urlFor("/auth/connect/${java.net.URLEncoder.encode(code.trim(), "UTF-8")}"))
            .header("Accept", "application/json")
            .get()
            .build()
        val response = http.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw it.toError(body)
            return decode<PairingSession>(body).token
        }
    }

    /** Note: unlike most list endpoints, `/api/project` returns a bare array, not a data envelope. */
    suspend fun projects(): List<Project> =
        OpenCodeJson.decodeFromString<List<Project>>(get("/api/project"))

    suspend fun sessions(
        directory: String? = null,
        projectID: String? = null,
        search: String? = null,
        limit: Int = 60,
    ): List<SessionInfo> = OpenCodeJson.decodeFromString<DataEnvelope<List<SessionInfo>>>(
        get(
            "/api/session",
            buildMap {
                put("directory", directory)
                put("project", projectID)
                put("search", search)
                put("limit", limit.toString())
            }
        )
    ).data

    suspend fun session(sessionID: String): SessionInfo =
        decode<DataEnvelope<SessionInfo>>(get("/api/session/$sessionID")).data

    /**
     * Loads the newest [limit] messages.
     *
     * `order=asc` with a limit returns the *oldest* N, which silently hides the most recent turns
     * once a session grows past the limit — the phone then looks frozen while the agent is
     * visibly working. Descending and reversing yields the tail of the conversation, which is the
     * part a person actually wants.
     */
    suspend fun messages(sessionID: String, limit: Int = 200): List<MessageInfo> =
        OpenCodeJson.decodeFromString<MessagesResponse>(
            get("/api/session/$sessionID/message", mapOf("limit" to limit.toString(), "order" to "desc"))
        ).data.reversed()

    suspend fun createSession(
        directory: String,
        title: String? = null,
        agent: String? = null,
        model: ModelRef? = null,
    ): SessionInfo {
        val body = buildJsonObject {
            directory.takeIf { it.isNotEmpty() }?.let { put("location", buildJsonObject { put("directory", it) }) }
            title?.let { put("title", it) }
            agent?.let { put("agent", it) }
            model?.let { put("model", buildJsonObject { put("id", it.id); put("providerID", it.providerID) }) }
        }
        return decode<DataEnvelope<SessionInfo>>(post("/api/session", body)).data
    }

    suspend fun deleteSession(sessionID: String) {
        delete("/api/session/$sessionID")
    }

    /**
     * Send a prompt.
     *
     * @param delivery `steer` injects the text into the in-flight turn; `queue` defers it until the
     *   current turn finishes. Matches the "steer vs queue" split used by Codex remote.
     */
    suspend fun prompt(
        sessionID: String,
        text: String,
        delivery: String? = null,
        agent: String? = null,
        files: List<FileAttachment> = emptyList(),
    ): String {
        val body = buildJsonObject {
            put("text", text)
            delivery?.let { put("delivery", it) }
            agent?.let { put("agents", buildJsonArrayOfAgent(it)) }
            if (files.isNotEmpty()) {
                put(
                    "files",
                    buildJsonArray {
                        files.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("uri", file.uri)
                                    file.name?.let { put("name", it) }
                                    file.description?.let { put("description", it) }
                                }
                            )
                        }
                    },
                )
            }
        }
        return post("/api/session/$sessionID/prompt", body)
    }

    /**
     * Uploads bytes to the host's filesystem.
     *
     * Attachments land under the server's own tmp directory rather than the project, so sending a
     * screenshot does not leave debris in the working tree that the agent might later read or
     * commit. Returns the absolute path the host wrote to.
     */
    /**
     * Uploads bytes to the host's filesystem.
     *
     * The request body is always sent as `application/octet-stream`; the server returns HTTP 415
     * for any other content type, including the file's real MIME type, so [mimeType] is carried as
     * metadata only.
     */
    suspend fun uploadFile(
        directory: String,
        path: String,
        content: ByteArray,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val request = newRequest(
            "/api/experimental/fs/write",
            buildMap {
                put("location[directory]", directory.ifBlank { null })
                put("path", path)
            },
        )
            .post(ProgressRequestBody(content, OCTET_STREAM, onProgress))
            .build()

        val response = http.newCall(request).await()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw it.toError(body)
            OpenCodeJson.decodeFromString<DataEnvelope<FileSystemWrite>>(body).data.path
        }
    }

    private fun buildJsonArrayOfAgent(agent: String) =
        kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject { put("name", agent) })
        }

    suspend fun interrupt(sessionID: String): Boolean =
        decode<InterruptResult>(post("/api/session/$sessionID/interrupt")).interrupted

    suspend fun pendingPermissions(): List<PermissionRequest> =
        OpenCodeJson.decodeFromString<DataEnvelope<List<PermissionRequest>>>(get("/api/permission/request")).data

    /** @param decision `once` | `always` | `reject` */
    suspend fun replyPermission(sessionID: String, requestID: String, decision: String) {
        post(
            "/api/session/$sessionID/permission/$requestID/reply",
            buildJsonObject { put("decision", decision) },
        )
    }

    /** Switches the agent driving a session's next turn. */
    suspend fun setAgent(sessionID: String, agent: String): String =
        post("/api/session/$sessionID/agent", buildJsonObject { put("agent", agent) })

    /** Switches the model driving a session's next turn. */
    suspend fun setModel(sessionID: String, model: ModelRef): String =
        post(
            "/api/session/$sessionID/model",
            buildJsonObject {
                put("model", buildJsonObject {
                    put("id", model.id)
                    put("providerID", model.providerID)
                    model.variant?.let { put("variant", it) }
                })
            },
        )

    /**
     * Location-scoped endpoints take a `location` **object** in the query string.
     *
     * opencode's query parser wants bracket notation (`location[directory]=...`). A JSON string
     * (`location={"directory":...}`) is rejected outright with `Expected object | undefined`, and
     * a plain `directory=` is silently ignored, which quietly scopes the request to the server's
     * own working directory.
     */
    private fun locationParams(directory: String?): Map<String, String?> =
        if (directory.isNullOrBlank()) emptyMap() else mapOf("location[directory]" to directory)

        /**
     * Agents available on this host.
     *
     * The host answers this with 400 when the location query is attached - `/api/model` tolerates
     * the same parameter, so it is specific to this endpoint. That failure was invisible: the
     * caller defaults an empty list, and an inherited session then had no agent rules to read,
     * so the approval chip reported the safest preset for an agent that allows nearly
     * everything. Retrying unscoped recovers the list and, with it, the real permission state.
     */
    suspend fun agents(directory: String?): List<AgentInfo> {
        val params = locationParams(directory)
        val agents = runCatching {
            OpenCodeJson.decodeFromString<AgentEnvelope>(get("/api/agent", params)).data
        }.getOrNull()
            ?: OpenCodeJson.decodeFromString<AgentEnvelope>(get("/api/agent")).data
        return agents.filter { !it.hidden }
    }

    suspend fun models(directory: String?): List<ModelInfo> =
        OpenCodeJson.decodeFromString<ModelEnvelope>(get("/api/model", locationParams(directory)))
            .data.filter { it.enabled }

    /** Every pending approval in [directory], in one request. */
    suspend fun pendingPermissions(directory: String? = null): List<PermissionRequest> =
        OpenCodeJson.decodeFromString<DataEnvelope<List<PermissionRequest>>>(
            get("/api/permission/request", locationParams(directory))
        ).data

    /**
     * Pending approvals for one session.
     *
     * More reliable than the location sweep when you already know which session you care about,
     * because it does not depend on location resolution at all.
     */
    suspend fun sessionPermissions(sessionID: String): List<PermissionRequest> =
        OpenCodeJson.decodeFromString<DataEnvelope<List<PermissionRequest>>>(
            get("/api/session/$sessionID/permission")
        ).data

    suspend fun renameSession(sessionID: String, title: String): SessionInfo =
        decode(patch("/api/session/$sessionID", buildJsonObject { put("title", title) }))

    /**
     * Replaces the session's permission ruleset.
     *
     * This is what the in-app auto-approval control writes. A catch-all `*`/`*` rule with effect
     * `allow` makes the session stop asking; with effect `ask` it makes it ask for everything.
     */
    suspend fun setPermissions(sessionID: String, rules: List<PermissionRule>): SessionInfo {
        val array = kotlinx.serialization.json.buildJsonArray {
            rules.forEach { rule ->
                add(
                    buildJsonObject {
                        put("action", rule.action)
                        put("resource", rule.resource)
                        put("effect", rule.effect)
                    }
                )
            }
        }
        val body = patch("/api/session/$sessionID", buildJsonObject { put("permissions", array) })

        // The host answers this PATCH with 204 and an empty body. Decoding it anyway failed with
        // "Expected start of the object '{', but had 'EOF'", which surfaced as a raw parser error
        // in a snackbar. Reading the session back keeps the caller's contract - it still gets the
        // updated session - instead of inventing one from an empty payload.
        if (body.isBlank()) return session(sessionID)

        return decode<DataEnvelope<SessionInfo>>(body).data
    }

    // ----------------------------------------------------------------- events

    /**
     * Server-sent event stream.
     *
     * The stream is live-only: it has no replay and no server-side buffering, so callers own
     * reconnection. Emits [OcEvent.ServerConnected] once the connection is established.
     */
    fun events(): Flow<OcEvent> = callbackFlow {
        val request = newRequest("/api/event")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                trySend(OcEvent.ServerConnected)
                val http = response
                if (!http.isSuccessful) {
                    val body = http.body?.string().orEmpty()
                    http.close()
                    close(http.toError(body))
                    return
                }
                try {
                    http.use { r ->
                        r.body.source().let { source ->
                            // opencode sends unnamed `message` events: the payload's own `type`
                            // field is the discriminator, and `:` lines are keep-alive comments.
                            //
                            // The client-wide read timeout is disabled for this endpoint, which
                            // leaves a half-open socket - host asleep, VPN dropped, laptop lid
                            // closed - blocking here forever with the app still claiming to be
                            // live. A per-read deadline turns that stall into an IOException so
                            // the flow closes and the repository reconnects and shows Offline.
                            source.timeout().timeout(SSE_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            while (!source.exhausted()) {
                                val line = source.readUtf8LineStrict() ?: break
                                if (line.isEmpty()) {
                                    continue
                                } else if (line.startsWith(":")) {
                                    // Keep-alive: activity, so the idle clock restarts.
                                    continue
                                } else if (line.startsWith("data:")) {
                                    decodeEvent(line.removePrefix("data:").trim())?.let { trySend(it) }
                                }
                            }
                        }
                    }
                    close()
                } catch (e: IOException) {
                    if (!call.isCanceled()) close(e)
                }
            }
        })

        awaitClose { call.cancel() }
    }


    /** Parses one SSE `data:` payload into a typed [OcEvent], or null when unrecognised. */
    fun decodeEvent(payload: String): OcEvent? {
        if (payload.isBlank()) return null
        val root = runCatching { OpenCodeJson.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val type = root.str("type") ?: return null
        val data = root.obj("data") ?: JsonObject(emptyMap())
        val sessionID = data.str("sessionID")
        val messageID = data.str("assistantMessageID").orEmpty()

        return when (type) {
            "server.connected" -> OcEvent.ServerConnected

            "session.created" -> data.str("sessionID")?.let { id ->
                SessionUpsert(
                    SessionInfo(
                        id = id,
                        projectID = data.str("projectID"),
                        title = data.str("title"),
                        agent = data.str("agent"),
                        location = data.obj("location")?.str("directory")?.let { LocationRef(it) },
                        time = SessionTime(
                            created = root.long("created") ?: 0,
                            updated = root.long("created") ?: 0,
                        ),
                    )
                )
            }

            "session.updated" -> runCatching {
                OpenCodeJson.decodeFromJsonElement(SessionInfo.serializer(), data)
            }.getOrNull()?.let { SessionUpsert(it) }

            "session.deleted" -> sessionID?.let { SessionDeleted(it) }

            "session.text.delta" -> if (sessionID != null) {
                TextDelta(sessionID, messageID, data.int("ordinal") ?: 0, data.str("delta").orEmpty())
            } else null

            "session.text.ended" -> if (sessionID != null) {
                TextEnded(sessionID, messageID, data.int("ordinal") ?: 0, data.str("text").orEmpty())
            } else null

            "session.reasoning.delta" -> if (sessionID != null) {
                ReasoningDelta(sessionID, messageID, data.int("ordinal") ?: 0, data.str("delta").orEmpty())
            } else null

            // The tool name and the tool arguments arrive on separate frames:
            // `tool.input.started` carries `name`, `tool.called` carries `input`.
            // Both map to ToolCalled and are merged downstream.
            "session.tool.input.started", "session.tool.called" -> {
                if (sessionID == null || messageID.isEmpty()) {
                    null
                } else {
                    ToolCalled(
                        sessionID = sessionID,
                        messageID = messageID,
                        toolID = data.str("id").orEmpty(),
                        name = data.str("name")?.takeIf { it.isNotBlank() },
                        input = data.obj("input"),
                        executed = data.bool("executed") ?: true,
                    )
                }
            }

            "session.tool.progress", "session.tool.success", "session.tool.failed" -> {
                if (sessionID == null || messageID.isEmpty()) {
                    null
                } else {
                    val toolID = data.str("id").orEmpty()
                    when (type) {
                        "session.tool.progress" -> ToolProgress(sessionID, messageID, toolID)
                        else -> ToolSettled(
                            sessionID, messageID, toolID,
                            ToolState(
                                status = if (type == "session.tool.failed") "error" else "completed",
                                input = data.obj("input"),
                                content = decodeToolContent(data),
                                error = data.obj("error")?.let {
                                    StructuredError(
                                        type = it.str("type").orEmpty(),
                                        message = it.str("message").orEmpty(),
                                        status = it.int("status"),
                                    )
                                } ?: data.str("error")?.let { StructuredError(type = "error", message = it) },
                                metadata = data.obj("metadata"),
                            ),
                        )
                    }
                }
            }

            "session.execution.started" -> sessionID?.let { Execution(it, "started") }
            "session.execution.succeeded" -> sessionID?.let { Execution(it, "succeeded") }
            "session.execution.failed" -> sessionID?.let {
                Execution(it, "failed", data.obj("error")?.let { e -> StructuredError(e.str("type").orEmpty(), e.str("message").orEmpty()) })
            }
            "session.execution.interrupted" -> sessionID?.let { Execution(it, "interrupted") }

            "session.usage.updated" -> if (sessionID != null) {
                Usage(sessionID, data.dbl("cost") ?: 0.0, decodeTokens(data.obj("tokens")))
            } else null

            "permission.asked" -> runCatching {
                OpenCodeJson.decodeFromJsonElement(PermissionRequest.serializer(), data)
            }.getOrNull()?.let { PermissionAsked(it) }

            "permission.replied", "permission.rejected" -> {
                val sid = sessionID ?: data.str("sessionID")
                val rid = data.str("id") ?: data.str("requestID")
                if (sid != null && rid != null) {
                    PermissionSettled(sid, rid, if (type == "permission.replied") data.str("reply").orEmpty() else "reject")
                } else null
            }

            "project.updated" -> runCatching {
                OpenCodeJson.decodeFromJsonElement(Project.serializer(), data)
            }.getOrNull()?.let { ProjectUpsert(it) }

            else -> null
        }
    }

    private fun decodeTokens(obj: JsonObject?): TokenUsage {
        obj ?: return TokenUsage()
        return TokenUsage(
            input = obj.long("input") ?: 0,
            output = obj.long("output") ?: 0,
            reasoning = obj.long("reasoning") ?: 0,
            cache = obj.obj("cache"),
        )
    }

    private fun decodeToolContent(data: JsonObject): List<ToolContent> {
        val content = data["content"] ?: return emptyList()
        return runCatching {
            OpenCodeJson.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ToolContent.serializer()), content
            )
        }.getOrDefault(emptyList())
    }

    companion object {
        /** The only content type the host's write endpoint accepts. */
        private const val OCTET_STREAM = "application/octet-stream"

        /**
         * How long the event stream may go without sending anything before it is considered dead.
         *
         * A deadline per read, not for the stream, so any event or keep-alive comment resets it.
         * Long enough not to fight a host that heartbeats, short enough that walking out of range
         * shows as Offline rather than as a stale "Live" that never updates again.
         */
        private const val SSE_IDLE_TIMEOUT_MS = 60_000L

        /**
         * Normalises user input into a usable base URL.
         *
         * A bare host gains the scheme and opencode's default port 4096 - typing `192.168.1.5`
         * and getting a refusal on port 80, with nothing in the URL to suggest why, is a bad
         * first run. A host the user already gave a scheme is left exactly as typed: https
         * implies 443, so appending 4096 there would break a working configuration.
         */
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s

            val hadScheme = s.startsWith("http://") || s.startsWith("https://")
            if (!hadScheme) {
                s = "http://$s"
                // Only when nothing follows the host: a colon there is already a port, and a
                // slash means a path we must not staple a port onto.
                val afterScheme = s.substringAfter("://")
                if (!afterScheme.contains(':') && !afterScheme.contains('/')) {
                    s = "$s:$DEFAULT_PORT"
                }
            }
            return s
        }

        /** opencode's own listening port. */
        private const val DEFAULT_PORT = 4096
    }
}

// ---------------------------------------------------------------- okhttp helpers

/**
 * Request body that reports bytes as they are written.
 *
 * OkHttp has no built-in upload progress, and `progressResponseBody` only covers a *download*;
 * wrapping the source here is what lets the UI show a real bar rather than a spinner that jumps
 * from 0 to done. Writes go through a chunked loop so progress actually advances.
 */
private class ProgressRequestBody(
    private val content: ByteArray,
    private val mime: String,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mime.toMediaTypeOrNull()

    override fun contentLength(): Long = content.size.toLong()

    /**
     * `writeTo` is blocking by contract, so this runs on OkHttp's writer thread and the callback
     * must not block. Callbacks are throttled to ~64 KiB steps: one per byte would flood both the
     * dispatcher and the UI.
     */
    override fun writeTo(sink: BufferedSink) {
        val total = content.size.toLong()
        onProgress(0, total)
        var reported = 0L
        content.forEachIndexed { index, byte ->
            sink.writeByte(byte.toInt())
            val written = (index + 1).toLong()
            if (written - reported >= PROGRESS_STEP_BYTES || written == total) {
                reported = written
                onProgress(written, total)
            }
        }
        onProgress(total, total)
    }

    private companion object {
        /** ~64 KiB: frequent enough to animate, sparse enough not to flood the dispatcher. */
        const val PROGRESS_STEP_BYTES = 64L * 1024L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(
                if (e is java.net.SocketTimeoutException) OpenCodeException(0, "Connection timed out") else e
            )
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
