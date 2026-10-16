@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.nezjusz.codedroid.ui.chat

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nezjusz.codedroid.core.data.ApprovalMode
import io.github.nezjusz.codedroid.core.data.ApprovalSetting
import io.github.nezjusz.codedroid.core.data.Attachment
import io.github.nezjusz.codedroid.core.data.AttachmentState
import io.github.nezjusz.codedroid.core.data.Entry
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.contextFraction
import io.github.nezjusz.codedroid.core.data.mergeToolOnlyTurns
import io.github.nezjusz.codedroid.core.data.Part
import io.github.nezjusz.codedroid.core.data.SavedServer
import io.github.nezjusz.codedroid.core.data.ServerRuntime
import io.github.nezjusz.codedroid.core.net.AgentInfo
import io.github.nezjusz.codedroid.core.net.PermissionRequest
import io.github.nezjusz.codedroid.ui.components.ContainedLoadingIndicator
import io.github.nezjusz.codedroid.ui.components.LinearWorkingBar
import io.github.nezjusz.codedroid.ui.components.LoadingIndicator
import io.github.nezjusz.codedroid.ui.components.PermissionCard
import io.github.nezjusz.codedroid.ui.components.SessionPhase
import io.github.nezjusz.codedroid.ui.components.StatusChip
import io.github.nezjusz.codedroid.ui.components.ToolCallsSection
import io.github.nezjusz.codedroid.ui.components.UsageLine
import io.github.nezjusz.codedroid.ui.components.VerticalGap
import io.github.nezjusz.codedroid.ui.components.formatTokens
import io.github.nezjusz.codedroid.ui.markdown.MarkdownText
import io.github.nezjusz.codedroid.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Most files a single prompt can carry. */
/** How often the context meter re-reads the newest message while a chat is open. */
private const val CONTEXT_REFRESH_MS = 30_000L

private const val MAX_ATTACHMENTS = 8

/** Per-file ceiling; bytes are buffered in RAM and base64'd into the prompt. */
private const val MAX_ATTACHMENT_MB = 10L
private const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024L * 1024L

private enum class Delivery(val label: String) { Steer("Steer"), Queue("Queue") }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    repository: OpenCodeRepository,
    serverId: String,
    sessionId: String,
    runtime: ServerRuntime?,
    server: SavedServer?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val handle = remember(serverId, sessionId) { repository.transcript(serverId, sessionId) }
    val entries by handle.entries.collectAsStateWithLifecycle()
    val loading by handle.isLoading.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var delivery by remember { mutableStateOf(Delivery.Steer) }
    var agent by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var agentSheet by remember { mutableStateOf(false) }
    var approvalSheet by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf(ApprovalSetting.Unknown) }
    var applyingMode by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    val context = LocalContext.current

    /**
     * Reads whatever a picker returned and appends it to the draft's attachments.
     *
     * Shared by both pickers so the cap, the size limit and the error messages behave identically
     * whether the bytes came from a photo or from a file.
     */
    fun takeUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = MAX_ATTACHMENTS - attachments.size
        if (room <= 0) {
            scope.launch { snackbars.showSnackbar("Up to $MAX_ATTACHMENTS attachments per message") }
            return
        }
        val picked = uris.take(room).mapNotNull { readAttachment(context, it, MAX_ATTACHMENT_BYTES) }
        if (picked.isEmpty()) {
            scope.launch { snackbars.showSnackbar("Could not read those files (max $MAX_ATTACHMENT_MB MB each)") }
        }
        attachments = attachments + picked
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeUris(uris)
    }

    // The system photo picker, which needs no permission: it hands back a read grant for only the
    // items the user chose, so READ_MEDIA_IMAGES is never requested. androidx resolves it in
    // three steps - ACTION_PICK_IMAGES on API 33+, an OEM system app on older devices that has
    // one, and ACTION_OPEN_DOCUMENT otherwise - which covers minSdk 26 without branching here.
    // MAX_ATTACHMENTS stays under MediaStore.getPickImagesMaxLimit()'s documented floor of 100,
    // so the contract's own limit check cannot reject it.
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)
    ) { uris ->
        takeUris(uris)
    }

    val session = runtime?.sessions?.firstOrNull { it.id == sessionId }
    val busy = runtime?.busySessions?.contains(sessionId) == true
    val permissions = runtime?.permissions?.values?.filter { it.sessionID == sessionId }.orEmpty()

    LaunchedEffect(sessionId) { handle.load() }

    // Keep the context meter moving during a turn. Usage is written onto a message when the turn
    // ends, so between the start and the end there is nothing to refresh *from* - the bar would
    // sit at its opening value for the whole of a long reply. A single-message read every half
    // minute is cheap enough to just poll, and it catches a host that reports usage incrementally.
    LaunchedEffect(sessionId) {
        while (true) {
            delay(CONTEXT_REFRESH_MS)
            handle.refreshUsage()
        }
    }

    LaunchedEffect(session?.permissions) {
        approval = ApprovalSetting.from(
            session?.permissions,
            agents.firstOrNull { it.id == (session?.agent ?: agent) }?.permissions,
        )
    }

    // The context ceiling lives on the model list, so refresh it when the session's model changes.
    LaunchedEffect(sessionId, session?.model?.id) {
        handle.setContextLimit(repository.contextLimitFor(serverId, session?.model))
    }
    val contextUsed by handle.contextUsed.collectAsStateWithLifecycle()
    val contextLimit by handle.contextLimit.collectAsStateWithLifecycle()
    // 0f while the ceiling is unknown, which the top bar reads as "show nothing" rather than 0%.
    val ctxFraction = remember(contextUsed, contextLimit) {
        contextFraction(contextUsed, contextLimit)
    }

    LaunchedEffect(sessionId, server?.directory) {
        agents = repository.agents(serverId, session?.directory ?: server?.directory)
        if (agent == null) agent = session?.agent ?: agents.firstOrNull()?.id

        // The agent's rules are what an inherited session runs under, so the chip cannot be
        // resolved until this has arrived.
        approval = ApprovalSetting.from(
            session?.permissions,
            agents.firstOrNull { it.id == (session?.agent ?: agent) }?.permissions,
        )
    }

    // Adjacent tool-only turns collapse into one disclosure; without this a busy turn renders
    // as a stack of identical "Tool calls" rows. Computed here rather than at the list so the
    // scroll effect below works from the same list the reader actually sees.
    val display = remember(entries) { entries.mergeToolOnlyTurns() }

    // Follow the tail until the reader deliberately scrolls away from it.
    //
    // Only sampled while the list is being dragged. Sampling `canScrollForward` on its own would
    // read "content is below me" the instant a streaming turn appends, which is true even while
    // the reader is pinned to the newest line, and would switch following off by itself.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.isScrollInProgress) listState.canScrollForward else null
        }.collect { scrolledAway -> if (scrolledAway != null) followTail = !scrolledAway }
    }

    // Moves on every streamed delta, including text growing *inside* the final entry - the entry
    // count stays put while the model keeps writing to the same message, so keying on the count
    // would stop following halfway through a reply.
    val tailSize = remember(display) {
        display.lastOrNull()?.let { last ->
            if (last is Entry.Assistant) {
                last.parts.sumOf { part ->
                    when (part) {
                        is Part.Text -> part.text.length
                        is Part.Reasoning -> part.text.length
                        else -> 1
                    }
                }
            } else {
                display.size
            }
        } ?: 0
    }

    // Pinned to the *end* of the final entry. scrollToItem alone would put that entry's first
    // line at the top of the screen, so a long answer would scroll up to its own beginning with
    // every token. The large offset is clamped by the layout to the end of the item.
    LaunchedEffect(tailSize, followTail) {
        if (followTail && display.isNotEmpty()) {
            listState.scrollToItem(display.lastIndex, Int.MAX_VALUE)
        }
    }

    // Widen the window when the reader reaches the top, rather than holding the whole history
    // from the start. Guarded on not following so the tail is never yanked by a page load.
    val hasOlder by handle.hasOlder.collectAsStateWithLifecycle()
    val loadingOlder by handle.isLoadingOlder.collectAsStateWithLifecycle()
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first ->
                if (first <= 1 && hasOlder && !loadingOlder) handle.loadMore()
            }
    }

    /**
     * Picks files, reading each into memory.
     *
     * Attachments only upload on send: picking four screenshots should not immediately push four
     * uploads over the network, and the prompt itself can still be edited in between.
     */
    fun pickFiles() {
        filePicker.launch(arrayOf("*/*"))
    }

    fun pickMedia() {
        mediaPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    fun send() {
        val text = draft.trim()
        val pending = attachments
        if (text.isEmpty() || sending || uploading) return
        if (pending.isEmpty() && text.isEmpty()) return

        sending = true
        draft = ""
        val sentText = text

        scope.launch {
            // Upload first, then send the prompt referencing what landed. A failed upload is
            // reported rather than silently sending a prompt with a dangling reference.
            var files = emptyList<io.github.nezjusz.codedroid.core.net.FileAttachment>()
            if (pending.isNotEmpty()) {
                uploading = true
                val batch = repository.uploadAll(serverId, pending) { id, state ->
                    attachments = attachments.map { if (it.id == id) it.withState(state) else it }
                }
                uploading = false

                if (!batch.allSucceeded) {
                    // Keep only the failures so the prompt text is not lost and a retry does not
                    // re-upload whatever already landed.
                    attachments = batch.failed.map { (attachment, _) -> attachment }
                    draft = sentText
                    sending = false
                    snackbars.showSnackbar(
                        "${batch.failed.size} file(s) failed: ${batch.failed.first().second}",
                    )
                    return@launch
                }
                attachments = emptyList()
                files = batch.attachments
            }

            repository.sendPrompt(
                serverId = serverId,
                sessionID = sessionId,
                text = sentText,
                delivery = when (delivery) {
                    Delivery.Steer -> if (busy) "steer" else null
                    Delivery.Queue -> "queue"
                },
                agent = agent,
                files = files,
            ).onFailure {
                draft = sentText
                snackbars.showSnackbar(it.message ?: "Could not send the prompt")
            }
            sending = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            // The context meter gets its own full-width strip rather than a chip in the title
            // slot. That slot already holds the title, the host name and the model, and it is
            // bounded by the navigation icon and the actions, so anything added last there was
            // squeezed down to a single glyph.
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                session?.title?.takeIf { it.isNotBlank() } ?: "Session",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // The host name is the one item here allowed to give up space: it is
                                // the least load-bearing, and letting it ellipsize keeps the model
                                // chip whole instead of truncating both.
                                Text(
                                    server?.displayName.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                session?.model?.let {
                                    StatusChip(
                                        label = it.id,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        AnimatedVisibility(visible = busy, enter = fadeIn(), exit = fadeOut()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                IconButton(onClick = { scope.launch { repository.interrupt(serverId, sessionId) } }) {
                                    Icon(
                                        Icons.Filled.Pause,
                                        contentDescription = "Interrupt",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                // Hidden entirely when the host reports no context ceiling, rather than showing
                // a misleading 0%.
                if (ctxFraction > 0f) {
                    ContextBar(fraction = ctxFraction, used = contextUsed)
                }
            }
        },
        bottomBar = {
            Column {
                if (busy) {
                    LinearWorkingBar(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Standing warning whenever the session will not ask before acting.
                if (approval.autoApproves) {
                    ApprovalBanner(
                        text = "Auto-approving everything in this session",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                permissions.forEach { request ->
                    PermissionCard(
                        request = request,
                        replying = replyingTo == request.id,
                        onReply = { reply ->
                            replyingTo = request.id
                            scope.launch {
                                val ok = repository.replyPermission(serverId, sessionId, request.id, reply)
                                if (!ok) snackbars.showSnackbar("Could not send the reply")
                                replyingTo = null
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                AttachmentTray(
                    attachments = attachments,
                    enabled = !uploading && !sending,
                    onRemove = { id -> attachments = attachments.filterNot { it.id == id } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { send() },
                    enabled = !sending && !uploading,
                    sending = sending,
                    busy = busy,
                    delivery = delivery,
                    onDeliveryChange = { delivery = it },
                    agentLabel = agent ?: "build",
                    onAgentClick = { agentSheet = true },
                    approval = approval,
                    onApprovalClick = { approvalSheet = true },
                    canAttach = attachments.size < MAX_ATTACHMENTS,
                    onAttach = { pickFiles() },
                    onAttachMedia = { pickMedia() },
                    attaching = uploading,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    ContainedLoadingIndicator()
                }

                entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        VerticalGap(10)
                        Text(
                            "Nothing here yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        VerticalGap(4)
                        Text(
                            "Send a prompt to start working on the host",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(display, key = { it.id }) { entry ->
                            when (entry) {
                                is Entry.User -> UserBubble(entry)
                                is Entry.System -> SystemNote(entry)
                                is Entry.Assistant -> AssistantBlock(entry)
                            }
                        }
                    }

                    // Only reachable if the reader has scrolled away from the tail, which is the
                    // one moment where being yanked down would lose their place.
                    AnimatedVisibility(
                        visible = !followTail,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                scope.launch {
                                    if (display.isNotEmpty()) {
                                        listState.animateScrollToItem(display.lastIndex)
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Jump to the latest message",
                            )
                        }
                    }
                }
            }
        }
    }

    if (agentSheet) {
        AgentPicker(
            agents = agents,
            current = agent,
            onPick = { agent = it; agentSheet = false },
            onDismiss = { agentSheet = false },
        )
    }

    if (approvalSheet) {
        ApprovalModeSheet(
            current = approval,
            busy = applyingMode,
            onPick = { mode ->
                applyingMode = true
                approvalSheet = false
                scope.launch {
                    approval = ApprovalSetting(mode, mode.rules)
                    repository.setApprovalMode(serverId, sessionId, mode)
                        .onFailure { snackbars.showSnackbar(it.message ?: "Could not change approval mode") }
                    applyingMode = false
                }
            },
            onDismiss = { approvalSheet = false },
        )
    }
}

/** Standing banner shown whenever the session is not going to ask. */
@Composable
private fun ApprovalBanner(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = content,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

@Composable
private fun ApprovalModeSheet(
    current: ApprovalSetting,
    busy: Boolean,
    onPick: (ApprovalMode) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Auto approval", style = MaterialTheme.typography.titleMedium)
            VerticalGap(2)
            Text(
                "Applies to this session only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // When the host's rules are none of the presets, all three would render unselected
            // with nothing explaining why. Say what is actually in force instead.
            if (current.mode == null) {
                VerticalGap(8)
                Text(
                    current.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            VerticalGap(12)

            ApprovalMode.entries.forEach { mode ->
                val selected = mode == current.mode
                val tint = if (mode == ApprovalMode.AutoApproveAll) {
                    MaterialTheme.colorScheme.error
                } else if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            if (selected) tint.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable(enabled = !busy) { onPick(mode) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) tint else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            mode.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                VerticalGap(4)
            }
            VerticalGap(24)
        }
    }
}

@Composable
private fun UserBubble(entry: Entry.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            // Prompts are rendered as markdown too: agents quote paths, commands and diffs back.
            MarkdownText(
                source = entry.text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SystemNote(entry: Entry.System) {
    Text(
        text = entry.text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

@Composable
private fun AssistantBlock(entry: Entry.Assistant) {
    // Tools are lifted out of the flow and rendered as one collapsible section, so a turn with
    // twenty edits reads as prose plus a single line rather than twenty cards.
    val tools = entry.parts.filterIsInstance<Part.Tool>()
    val narrative = entry.parts.filterNot { it is Part.Tool }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        narrative.forEach { part ->
            when (part) {
                is Part.Text -> if (part.text.isNotBlank()) {
                    MarkdownText(
                        source = part.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    )
                }

                is Part.Reasoning -> ReasoningBlock(part, streaming = entry.busy)

                is Part.Tool -> Unit // handled by ToolCallsSection
            }
        }

        if (narrative.none { it is Part.Text && it.text.isNotBlank() } && entry.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ToolCallsSection(tools = tools, running = entry.busy, sectionKey = entry.id)

        entry.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    error.message.ifBlank { error.type },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (!entry.busy && (entry.cost > 0 || (entry.tokens?.total ?: 0L) > 0L) && narrative.isNotEmpty()) {
            // Only when the turn said something. A tool-only turn renders as a single collapsed
            // row, and a stray token count under each one just reads as noise.
            UsageLine(cost = entry.cost, tokens = entry.tokens?.total ?: 0L)
        }
    }
}

/**
 * Context-window occupancy, as a full-width strip under the app bar.
 *
 * A bar rather than a chip because it cannot be truncated: however long the session title and
 * model id get, this keeps its whole width. The percentage rides alongside so the number is still
 * readable at a glance instead of having to be eyeballed off the bar's length.
 */
@Composable
private fun ContextBar(fraction: Float, used: Long) {
    val tint = contextTint(fraction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            color = tint,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f).height(4.dp),
        )
        Text(
            "${(fraction * 100).toInt()}% · ${formatTokens(used)}",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Green while there is room, then a warning, then an error as the window fills. */
@Composable
private fun contextTint(fraction: Float): Color = when {
    fraction >= 0.9f -> MaterialTheme.colorScheme.error
    fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
    else -> LocalStatusColors.current.running
}

@Composable
private fun ReasoningBlock(part: Part.Reasoning, streaming: Boolean) {
    // Keyed on the part: unkeyed, Compose reuses one slot across messages and a block left open
    // in an earlier turn would greet you already expanded.
    var expanded by remember(part.ordinal) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (expanded) 1f else 0.65f, label = "reasonAlpha")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "Reasoning" else "Reasoning · ${part.text.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val body: @Composable () -> Unit = {
            Text(
                text = part.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (expanded && streaming) {
            // No transition while the text is still arriving. The target height changes on every
            // delta, so an expand animation restarts each time and reads as the panel reopening
            // over and over while yanking the viewport to its top.
            body()
        } else {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                body()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    sending: Boolean,
    busy: Boolean,
    delivery: Delivery,
    onDeliveryChange: (Delivery) -> Unit,
    agentLabel: String,
    onAgentClick: () -> Unit,
    approval: ApprovalSetting,
    onApprovalClick: () -> Unit,
    canAttach: Boolean,
    onAttach: () -> Unit,
    onAttachMedia: () -> Unit,
    attaching: Boolean,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onAgentClick,
                    label = { Text(agentLabel, maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Filled.SmartToy, contentDescription = null, Modifier.size(16.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )

                AssistChip(
                    onClick = { onDeliveryChange(if (delivery == Delivery.Steer) Delivery.Queue else Delivery.Steer) },
                    label = { Text(delivery.label) },
                    leadingIcon = {
                        Icon(
                            if (delivery == Delivery.Steer) Icons.Filled.Bolt else Icons.Filled.Pause,
                            contentDescription = null,
                            Modifier.size(16.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )

                if (busy) {
                    AssistChip(
                        onClick = { },
                        label = { Text("Working") },
                        leadingIcon = {
                            // Explicit size and colour: at smaller sizes the expressive shape
                            // renders too faint to see inside the chip's icon slot.
                            LoadingIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                ApprovalChip(setting = approval, onClick = onApprovalClick)
            }

            Row(verticalAlignment = Alignment.Bottom) {
                // Circular "+" on the left: it reads as "add something to this message" and keeps
                // the text field's full width, which the paperclip-on-the-right layout ate into.
                // The button is a menu rather than a direct action because the two pickers are
                // genuinely different: media goes through the permissionless photo picker, files
                // through the document picker.
                Box {
                    FilledTonalIconButton(
                        onClick = { attachMenuOpen = true },
                        enabled = canAttach && !attaching,
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                    ) {
                        if (attaching) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Attach media or a file",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photos & videos") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onAttachMedia()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onAttach()
                            },
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                busy && delivery == Delivery.Steer -> "Steer the running turn…"
                                busy -> "Queue a follow-up…"
                                else -> "Send a prompt…"
                            }
                        )
                    },
                    maxLines = 6,
                    shape = MaterialTheme.shapes.large,
                )

                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && draft.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (sending) {
                        LoadingIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (busy) {
                Text(
                    text = when (delivery) {
                        Delivery.Steer -> "Your next message is injected into the running turn."
                        Delivery.Queue -> "Your message waits until the current turn finishes."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (attaching) {
                Text(
                    text = "Uploading attachments…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Chip that reflects, and changes, how much the agent may do without asking. */
@Composable
private fun ApprovalChip(setting: ApprovalSetting, onClick: () -> Unit) {
    // Tint by what the agent will actually do, so a host ruleset that allows broadly warns like
    // a full auto-approve rather than sitting greyed out beside it.
    val tint = when {
        setting.mode == ApprovalMode.AutoApproveAll || setting.autoApproves ->
            MaterialTheme.colorScheme.error
        setting.mode == ApprovalMode.AutoRead -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = setting.label,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        },
        leadingIcon = {
            Icon(
                if (setting.mode == ApprovalMode.AskEveryTime) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
                contentDescription = "Auto approval",
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = tint.copy(alpha = 0.10f)),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentPicker(
    agents: List<AgentInfo>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Agent", style = MaterialTheme.typography.titleMedium)
            VerticalGap(8)
            agents.forEach { option ->
                val selected = option.id == current
                FilterRowItem(
                    title = option.name.ifBlank { option.id },
                    subtitle = option.description,
                    selected = selected,
                    onClick = { onPick(option.id) },
                )
            }
            if (agents.isEmpty()) {
                Text(
                    "No agents reported by this host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            VerticalGap(24)
        }
    }
}

@Composable
private fun FilterRowItem(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val caption = if (selected) "current agent" else subtitle
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    VerticalGap(6)
}
