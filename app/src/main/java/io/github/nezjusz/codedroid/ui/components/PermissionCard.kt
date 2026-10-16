package io.github.nezjusz.codedroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.core.data.Part
import io.github.nezjusz.codedroid.core.data.toolSummary
import io.github.nezjusz.codedroid.core.net.PermissionRequest
import io.github.nezjusz.codedroid.ui.theme.LocalStatusColors

/**
 * Inline approval card.
 *
 * This is the blocking UI of the whole app: the agent cannot continue until the user answers, so
 * the card is visually loud, shows exactly what is being asked for, and offers the three replies
 * the API accepts — `once`, `always`, `reject`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionCard(
    request: PermissionRequest,
    replying: Boolean,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    var expanded by remember(request.id) { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                )
            )
            .clip(MaterialTheme.shapes.large)
            .background(status.waiting.copy(alpha = 0.10f))
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(status.waiting.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = status.waiting,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Needs approval",
                    style = MaterialTheme.typography.labelSmall,
                    color = status.waiting,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                request.resources.forEach { resource ->
                    MonoBlock(text = resource, maxLines = 6)
                }
                if (request.resources.isEmpty()) {
                    request.message?.takeIf { it.isNotBlank() }?.let { MonoBlock(it, maxLines = 8) }
                }

                if (replying) {
                    Text(
                        "Sending reply…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ReplyRow(onReply = onReply)
                }
            }
        }
    }
}

/**
 * Reply controls.
 *
 * Three equal-width actions, with the destructive option visually separated from the two
 * granting paths — approving is the expected action, so it stays on the left in tonal colour.
 */
@Composable
private fun ReplyRow(onReply: (String) -> Unit) {
    EqualWidthRow {
        FilledTonalButton(onClick = { onReply("once") }, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Once")
        }
        FilledTonalButton(onClick = { onReply("always") }, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Always")
        }
        Button(
            onClick = { onReply("reject") },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Deny") }
    }
}

/**
 * Every tool call in an assistant turn, behind one control.
 *
 * Tool chatter dominates a coding transcript, so a turn with twenty edits and shell calls renders
 * as one line until asked. The section opens itself while the turn is still running, because that
 * is when you actually want to watch, and stays shut once it settles.
 */
@Composable
fun ToolCallsSection(
    tools: List<Part.Tool>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) return

    var expanded by remember(tools.size, running) { mutableStateOf(running) }
    val status = LocalStatusColors.current

    val failed = tools.count { it.status == "error" }
    val stillRunning = tools.any { it.status == "running" }
    val totalMs = tools.mapNotNull { it.endedAt?.let { end -> end - it.startedAt } }.sum()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (stillRunning) Icons.Filled.Bolt else Icons.Filled.Build,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (stillRunning) status.running else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Tool calls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = summaryLabel(tools.size, failed, totalMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (failed > 0) {
                StatusChip(label = "$failed failed", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse tool calls" else "Expand tool calls",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tools.forEach { tool -> ToolCallCard(tool) }
            }
        }
    }
}

private fun summaryLabel(count: Int, failed: Int, totalMs: Long): String {
    val calls = "$count call${if (count == 1) "" else "s"}"
    val timing = if (totalMs >= 1000) " · %.1fs".format(totalMs / 1000.0) else ""
    return "$calls$timing"
}

/**
 * Collapsible tool call.
 *
 * Kept collapsed by default inside [ToolCallsSection]: the section already conveys that tools ran,
 * so an individual call should only cost space when you go looking for it.
 */
@Composable
fun ToolCallCard(
    part: io.github.nezjusz.codedroid.core.data.Part.Tool,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val status = LocalStatusColors.current
    var expanded by remember(part.id) { mutableStateOf(defaultExpanded) }

    val (statusLabel, statusColor) = when (part.status) {
        "running" -> "Running" to status.running
        "completed" -> "Done" to status.idle
        "error" -> "Failed" to MaterialTheme.colorScheme.error
        else -> "Queued" to status.idle
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(label = part.name.ifBlank { "tool" }, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            StatusChip(
                label = statusLabel,
                color = statusColor,
                icon = when (part.status) {
                    "completed" -> Icons.Filled.ExpandMore
                    "error" -> Icons.Filled.ExpandLess
                    else -> null
                },
            )
            Spacer(Modifier.weight(1f))
            val duration = part.endedAt?.let { (it - part.startedAt).coerceAtLeast(0) }
            if (duration != null && duration > 0) {
                Text(
                    "${duration / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val summary = part.input?.toolSummary(160).orEmpty()
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )
        }

        if (expanded) {
            part.input?.takeIf { it.isNotEmpty() }?.let { MonoBlock(it.toString(), maxLines = 14) }
            if (part.output.isNotBlank()) MonoBlock(part.output, maxLines = 30)
            part.error?.let { error ->
                Column {
                    Text(
                        "Error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MonoBlock(error, maxLines = 10)
                }
            }
        }
    }
}

/** Compact button row used in dialogs and empty states. */
@Composable
fun PrimaryActions(onCancel: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        onCancel?.let {
            OutlinedButton(onClick = it) { Text("Cancel") }
        }
        content()
    }
}

@Composable
fun SizedGap(height: Int) = Spacer(Modifier.height(height.dp))
