@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.nezjusz.codedroid.ui.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.core.data.ConnectionState
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.SavedServer
import io.github.nezjusz.codedroid.core.data.ServerRuntime
import io.github.nezjusz.codedroid.core.net.SessionInfo
import io.github.nezjusz.codedroid.ui.components.EmptyState
import io.github.nezjusz.codedroid.ui.components.LoadingIndicator
import io.github.nezjusz.codedroid.ui.components.SectionLabel
import io.github.nezjusz.codedroid.ui.components.SegmentedSelector
import io.github.nezjusz.codedroid.ui.components.SessionPhase
import io.github.nezjusz.codedroid.ui.components.StatusChip
import io.github.nezjusz.codedroid.ui.components.StatusDot
import io.github.nezjusz.codedroid.ui.components.UsageLine
import io.github.nezjusz.codedroid.ui.components.VerticalGap
import io.github.nezjusz.codedroid.ui.components.formatRelative
import io.github.nezjusz.codedroid.ui.components.phaseColor
import kotlinx.coroutines.launch

private enum class SessionFilter(val label: String) {
    All("All"), Running("Running"), Blocked("Needs approval")
}

@Composable
fun SessionsScreen(
    repository: OpenCodeRepository,
    serverId: String,
    runtime: ServerRuntime?,
    server: SavedServer?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onEditHost: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(SessionFilter.All) }
    var showNewSession by remember { mutableStateOf(false) }

    val sessions = runtime?.sessions.orEmpty()
    val visible = when (filter) {
        SessionFilter.All -> sessions
        SessionFilter.Running -> sessions.filter { it.id in runtime?.busySessions.orEmpty() }
        SessionFilter.Blocked -> runtime?.sessionsAwaitingApproval.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            server?.displayName ?: "Sessions",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        ConnectionSubtitle(runtime)
                        // Show the directory scope: it is the most likely reason the list looks
                        // smaller than expected, and it is otherwise invisible.
                        server?.directory?.takeIf { it.isNotBlank() }?.let { directory ->
                            Text(
                                text = directory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Refresh the session list. This is explicitly NOT destructive: it re-reads
                    // from the host and merges, it never deletes.
                    IconButton(
                        onClick = { scope.launch { repository.refresh(serverId) } },
                        enabled = runtime?.state != ConnectionState.Connecting,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh sessions")
                    }
                    IconButton(onClick = onEditHost) {
                        Icon(Icons.Filled.Settings, contentDescription = "Host settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            if (sessions.isNotEmpty() || runtime?.projects?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = { showNewSession = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New chat") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow(
                filter = filter,
                onFilter = { filter = it },
                runningCount = runtime?.sessionsBusy?.size ?: 0,
                blockedCount = runtime?.attention ?: 0,
            )

            when {
                runtime?.state == ConnectionState.Connecting && sessions.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(size = 40.dp)
                    }
                }

                sessions.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Filled.Forum,
                        title = "No sessions",
                        body = "Start a chat from the phone and the agent will begin working on the host.",
                        action = {
                            androidx.compose.material3.Button(onClick = { showNewSession = true }) {
                                Text("Start a chat")
                            }
                        },
                    )
                }

                visible.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Filled.Forum,
                        title = "Nothing here",
                        body = when (filter) {
                            SessionFilter.Running -> "No session is working right now."
                            SessionFilter.Blocked -> "No agent is waiting for approval."
                            SessionFilter.All -> "No sessions on this host yet."
                        },
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                phase = phaseOf(session, runtime),
                                onClick = { onOpenSession(session.id) },
                                onDelete = {
                                    scope.launch {
                                        repository.deleteSession(serverId, session.id)
                                        repository.refreshSessions(serverId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewSession) {
        NewSessionDialog(
            repository = repository,
            serverId = serverId,
            runtime = runtime,
            onDismiss = { showNewSession = false },
            onCreated = { sessionId ->
                showNewSession = false
                onOpenSession(sessionId)
            },
        )
    }
}

private fun phaseOf(session: SessionInfo, runtime: ServerRuntime?): SessionPhase {
    val blocked = runtime?.permissions?.values?.any { it.sessionID == session.id } == true
    return when {
        blocked -> SessionPhase.WaitingApproval
        runtime?.busySessions?.contains(session.id) == true -> SessionPhase.Running
        session.outcome == "failed" -> SessionPhase.Failed
        else -> SessionPhase.Idle
    }
}

@Composable
private fun ConnectionSubtitle(runtime: ServerRuntime?) {
    val state = runtime?.state ?: ConnectionState.Idle
    val (label, color) = when (state) {
        ConnectionState.Live -> "Live" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting -> "Connecting" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Offline -> "Offline" to MaterialTheme.colorScheme.error
        ConnectionState.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            ConnectionState.Connecting -> LoadingIndicator(modifier = Modifier.size(11.dp))
            else -> StatusDot(
                if (state == ConnectionState.Live) SessionPhase.Running else SessionPhase.Idle,
                size = 7.dp,
                pulse = false,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            // Any recorded error wins over the state label: the monitor reconnects within seconds, so
            // requiring Offline to display it made transient failures (a rate limit, a refused
            // connection) invisible until the app was reopened.
            text = runtime?.lastError?.takeIf { it.isNotBlank() } ?: label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterRow(
    filter: SessionFilter,
    onFilter: (SessionFilter) -> Unit,
    runningCount: Int,
    blockedCount: Int,
) {
    SegmentedSelector(
        options = SessionFilter.entries,
        selected = filter,
        onSelect = onFilter,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { option ->
        val count = when (option) {
            SessionFilter.Running -> runningCount
            SessionFilter.Blocked -> blockedCount
            SessionFilter.All -> 0
        }
        Text(
            text = if (count > 0) "${option.label} ($count)" else option.label,
            maxLines = 1,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    phase: SessionPhase,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val accent by animateColorAsState(phaseColor(phase), label = "phase")

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (phase == SessionPhase.WaitingApproval) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(phase = phase, size = 9.dp)

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = session.title?.takeIf { it.isNotBlank() } ?: "Untitled session",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatRelative(session.time.updated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.model?.let { model ->
                        StatusChip(
                            label = model.id,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (phase == SessionPhase.WaitingApproval) {
                    StatusChip(
                        label = "Waiting for your approval",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if ((session.tokens?.total ?: 0L) > 0L) {
                    UsageLine(cost = session.cost, tokens = session.tokens?.total ?: 0L)
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Session options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete session") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = accent.copy(alpha = 0.8f),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the session from the host. It cannot be undone from the phone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NewSessionDialog(
    repository: OpenCodeRepository,
    serverId: String,
    runtime: ServerRuntime?,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val projects = runtime?.projects.orEmpty()
    var title by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(runtime?.sessions?.firstOrNull()?.directory ?: projects.firstOrNull()?.canonical.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (projects.isNotEmpty()) {
                    Column {
                        Text(
                            "Directory",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        VerticalGap(4)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            projects.take(4).forEach { project ->
                                val selected = directory == project.canonical
                                FilterChip(
                                    selected = selected,
                                    onClick = { directory = project.canonical },
                                    label = {
                                        Text(
                                            project.canonical.substringAfterLast('/').ifBlank { project.canonical },
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = directory,
                    onValueChange = { directory = it },
                    label = { Text("Directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        repository.createSession(serverId, directory.trim(), title.trim().ifBlank { null })
                            .onSuccess { onCreated(it.id) }
                            .onFailure {
                                error = it.message ?: "Could not create the session"
                                busy = false
                            }
                    }
                },
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
