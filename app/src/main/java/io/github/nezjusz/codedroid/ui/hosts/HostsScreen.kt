@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.nezjusz.codedroid.ui.hosts

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.core.data.ConnectionState
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.SavedServer
import io.github.nezjusz.codedroid.core.data.ServerRuntime
import io.github.nezjusz.codedroid.ui.components.EmptyState
import io.github.nezjusz.codedroid.ui.components.LoadingIndicator
import io.github.nezjusz.codedroid.ui.components.SectionLabel
import io.github.nezjusz.codedroid.ui.components.StatusChip
import io.github.nezjusz.codedroid.ui.components.StatusDot
import io.github.nezjusz.codedroid.ui.components.VerticalGap
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HostsScreen(
    repository: OpenCodeRepository,
    servers: List<SavedServer>,
    runtimes: Map<String, ServerRuntime>,
    onOpenHost: (String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val totalBlockers = runtimes.values.sumOf { it.attention }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CodeDroid", fontWeight = FontWeight.SemiBold)
                        if (totalBlockers > 0) {
                            Text(
                                "$totalBlockers request${if (totalBlockers == 1) "" else "s"} waiting",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddHost,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Pair host") },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.Dns,
                    title = "No hosts yet",
                    body = "Pair this phone with an opencode server to start steering sessions from " +
                        "here. On the host, run:\n\nopencode api post /api/pair",
                    action = {
                        androidx.compose.material3.Button(onClick = onAddHost) { Text("Pair a host") }
                    },
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionLabel("Connected hosts", trailing = "${servers.size}") }

            items(servers, key = { it.id }) { server ->
                HostCard(
                    server = server,
                    runtime = runtimes[server.id],
                    onClick = { onOpenHost(server.id) },
                    onRefresh = { scope.launch { repository.refresh(server.id) } },
                    onEdit = { onEditHost(server.id) },
                    onRemove = { repository.removeServer(server.id) },
                    onToggleKeepAlive = { enabled ->
                        repository.updateServer(server.copy(keepAlive = enabled))
                    },
                )
            }
        }
    }
}

@Composable
private fun HostCard(
    server: SavedServer,
    runtime: ServerRuntime?,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val state = runtime?.state ?: ConnectionState.Idle
    val stateLabel = when (state) {
        ConnectionState.Live -> "Live"
        ConnectionState.Connecting -> "Connecting"
        ConnectionState.Offline -> "Offline"
        ConnectionState.Idle -> "Idle"
    }
    val blockers = runtime?.attention ?: 0
    val busy = runtime?.sessionsBusy?.size ?: 0

    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        text = server.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Host options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit pairing") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (server.keepAlive) "Stop background sync" else "Keep connected")
                            },
                            onClick = { menuOpen = false; onToggleKeepAlive(!server.keepAlive) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove host") },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state) {
                    ConnectionState.Live -> StatusDot(
                        io.github.nezjusz.codedroid.ui.components.SessionPhase.Running,
                        size = 8.dp,
                        pulse = false,
                    )

                    ConnectionState.Connecting -> LoadingIndicator(modifier = Modifier.size(14.dp))
                    else -> StatusDot(io.github.nezjusz.codedroid.ui.components.SessionPhase.Idle, size = 8.dp, pulse = false)
                }

                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                runtime?.info?.version?.let {
                    StatusChip(label = "v$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (blockers > 0 || busy > 0 || (runtime?.sessions?.size ?: 0) > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (blockers > 0) {
                        StatusChip(
                            label = "$blockers awaiting approval",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (busy > 0) {
                        StatusChip(
                            label = "$busy running",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val total = runtime?.sessions?.size ?: 0
                    if (blockers == 0 && busy == 0 && total > 0) {
                        StatusChip(
                            label = "$total sessions",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Shown whenever there is one, not only while Offline. The monitor retries after two seconds,
            // so a rate-limit or a transient refusal is recorded and then immediately back to
            // Connecting - gating on Offline meant the message was gone before it could be read,
            // and the only way to see it was to reopen the app.
            runtime?.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            server.directory?.let { directory ->
                Text(
                    text = directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
