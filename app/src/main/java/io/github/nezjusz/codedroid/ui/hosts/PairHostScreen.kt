@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.nezjusz.codedroid.ui.hosts

import java.util.UUID
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.SavedServer
import io.github.nezjusz.codedroid.core.net.OpenCodeClient
import io.github.nezjusz.codedroid.ui.components.LoadingIndicator
import io.github.nezjusz.codedroid.ui.components.SectionLabel
import io.github.nezjusz.codedroid.ui.components.SegmentedSelector
import kotlinx.coroutines.launch

private enum class Mode(val label: String) { PairingCode("Pairing code"), Password("Password") }

/**
 * Host setup.
 *
 * Pairing is the primary path: the host prints a short-lived code, the phone redeems it once and
 * stores the resulting long-lived token. Manual credentials remain available for hosts where the
 * operator would rather paste the service password.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairHostScreen(
    repository: OpenCodeRepository,
    existing: SavedServer?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(existing?.token.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var directory by remember { mutableStateOf(existing?.directory.orEmpty()) }
    var notifyPermissions by remember { mutableStateOf(existing?.notifyPermissions ?: true) }
    var notifyCompletion by remember { mutableStateOf(existing?.notifyCompletion ?: true) }
    var keepAlive by remember { mutableStateOf(existing?.keepAlive ?: true) }
    var mode by remember { mutableStateOf(if (existing == null) Mode.PairingCode else Mode.Password) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        error = null
        info = null
        val url = baseUrl.trim()
        if (url.isEmpty()) {
            error = "Enter the host address, for example 192.168.1.20:4096"
            return
        }

        busy = true
        scope.launch {
            runCatching {
                if (mode == Mode.PairingCode) {
                    if (code.isBlank()) error = "Paste the pairing code from the host"
                    else {
                        val server = repository.pair(url, code.trim())
                        repository.updateServer(
                            server.copy(
                                name = name.ifBlank { server.name },
                                directory = directory.ifBlank { server.directory },
                                notifyPermissions = notifyPermissions,
                                notifyCompletion = notifyCompletion,
                                keepAlive = keepAlive,
                            )
                        )
                    }
                } else {
                    if (password.isBlank()) {
                        error = "Enter the server password"
                    } else {
                        val client = OpenCodeClient(url, password.trim())
                        val details = client.info()
                        val normalized = OpenCodeClient.normalizeBaseUrl(url)
                        val edited = SavedServer(
                            // Keeps the existing id when editing. This used to be passed to
                            // addServer, which appends, so editing a host stored a second entry
                            // sharing its id. The hosts list is keyed on id, so the duplicate
                            // threw on the next composition - and because it was persisted, on
                            // every launch afterwards, with clearing app data as the only way out.
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.ifBlank { normalized.removePrefix("http://").removePrefix("https://") },
                            baseUrl = normalized,
                            token = password.trim(),
                            directory = directory.ifBlank { existing?.directory },
                            notifyPermissions = notifyPermissions,
                            notifyCompletion = notifyCompletion,
                            keepAlive = keepAlive,
                        )
                        if (existing == null) repository.addServer(edited)
                        else repository.updateServer(edited)
                        info = "Connected to opencode ${details.version} (pid ${details.pid})"
                    }
                }
            }.onFailure { e ->
                error = friendlyError(e)
            }
            busy = false
            if (error == null) onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Pair a host" else "Host settings") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Host address") },
                placeholder = { Text("192.168.1.20:4096") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = directory,
                onValueChange = { directory = it },
                label = { Text("Working directory (optional)") },
                placeholder = { Text("/home/user/projects") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                supportingText = {
                    Text("New sessions start here. Leave blank to use every project on the host.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Credentials")

            ModeToggle(mode = mode, onChange = { mode = it })

            AnimatedVisibility(visible = mode == Mode.PairingCode, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Pairing code") },
                        placeholder = { Text("xxxxxxxx-xxxxxxxx") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Create a code on the host",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                "opencode api post /api/pair",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Codes are single-use and expire in 5 minutes. The phone exchanges it for a " +
                                    "long-lived token and never contacts the host again for this step.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = mode == Mode.Password, enter = fadeIn(), exit = fadeOut()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Server password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    supportingText = { Text("Found in the host's service.json, or set with OPENCODE_SERVER_PASSWORD.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (existing != null) {
                SectionLabel("Notifications")
                ToggleRow(
                    title = "Permission requests",
                    subtitle = "Alert me when an agent is blocked waiting for approval",
                    checked = notifyPermissions,
                    onChange = { notifyPermissions = it },
                )
                ToggleRow(
                    title = "Session completion",
                    subtitle = "Tell me when a turn finishes or fails",
                    checked = notifyCompletion,
                    onChange = { notifyCompletion = it },
                )
                ToggleRow(
                    title = "Background connection",
                    subtitle = "Keep the event stream alive so alerts arrive while the app is closed",
                    checked = keepAlive,
                    onChange = { keepAlive = it },
                )
            }

            error?.let { ErrorBanner(it) }
            info?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (busy) {
                    LoadingIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…")
                } else {
                    Text(
                        if (existing == null) "Pair host" else "Save",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeToggle(mode: Mode, onChange: (Mode) -> Unit) {
    SegmentedSelector(
        options = Mode.entries,
        selected = mode,
        onSelect = onChange,
        modifier = Modifier.fillMaxWidth(),
        height = 48.dp,
    ) { option ->
        Text(option.label, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(14.dp),
        )
    }
}

private fun friendlyError(e: Throwable): String = when (e) {
    is io.github.nezjusz.codedroid.core.net.OpenCodeException -> when {
        e.status == 401 -> "The host rejected that code or password. Generate a fresh pairing code and retry."
        e.status == 404 -> "That host does not look like an opencode server (no /api/info)."
        e.status == 0 -> "Could not reach the host. Check the address, port and Wi-Fi."
        else -> "${e.message} (HTTP ${e.status})"
    }

    is java.net.ConnectException -> "Connection refused. Is `opencode serve` running and bound to that address?"
    is java.net.SocketTimeoutException -> "The host did not answer in time."
    is java.net.UnknownHostException -> "Cannot resolve that hostname."
    else -> e.message ?: e::class.simpleName.orEmpty()
}
