package io.github.nezjusz.codedroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.ui.theme.LocalStatusColors
import java.util.Locale

/** Session lifecycle as the UI understands it. */
enum class SessionPhase { Running, WaitingApproval, Idle, Failed }

@Composable
fun phaseColor(phase: SessionPhase): Color {
    val status = LocalStatusColors.current
    return when (phase) {
        SessionPhase.Running -> status.running
        SessionPhase.WaitingApproval -> status.waiting
        SessionPhase.Idle -> status.idle
        SessionPhase.Failed -> MaterialTheme.colorScheme.error
    }
}

@Composable
fun phaseIcon(phase: SessionPhase): ImageVector = when (phase) {
    SessionPhase.Running -> Icons.Filled.Bolt
    SessionPhase.WaitingApproval -> Icons.Filled.Pause
    SessionPhase.Idle -> Icons.Filled.HourglassTop
    SessionPhase.Failed -> Icons.Filled.Error
}

/**
 * Small status indicator.
 *
 * A running session breathes via a pulsing alpha so a glance at a long list still answers
 * "which of these is working right now?".
 */
@Composable
fun StatusDot(
    phase: SessionPhase,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 10.dp,
    pulse: Boolean = true,
) {
    val color = phaseColor(phase)
    val transition = rememberInfiniteTransition(label = "status")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val animated by animateColorAsState(color, label = "dot")

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (pulse && phase == SessionPhase.Running) alpha else 1f)
            .clip(CircleShape)
            .background(animated)
    )
}

/** Pill used for tool status, model names and other compact metadata. */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Thin monospace block used for tool output and command echoes. */
@Composable
fun MonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val status = LocalStatusColors.current
    Text(
        text = text,
        style = io.github.nezjusz.codedroid.ui.theme.MonoTextStyle,
        color = status.codeForeground,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(status.codeBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        maxLines = maxLines,
    )
}

/** Compact "3.2k tokens · $0.04" line. */
@Composable
fun UsageLine(cost: Double, tokens: Long, modifier: Modifier = Modifier) {
    if (cost <= 0.0 && tokens <= 0) return
    val parts = buildList {
        if (tokens > 0) add("${formatTokens(tokens)} tokens")
        if (cost > 0.0) add("$" + String.format(Locale.US, "%.4f", cost))
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

fun formatRelative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "—"
    val delta = (now - timestamp).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

/** Section divider with an expressive label treatment. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun VerticalGap(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun CheckMark(tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
}

@Composable
fun UpArrow() {
    Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
}

/**
 * Connected row of equal-width choices.
 *
 * `androidx.compose.material3.ButtonGroup` would be the expressive-native way to do this, but as
 * of material3 1.5.0-beta01 its `content` parameter is a plain (non-composable)
 * `ButtonGroupScope.() -> Unit`, so it cannot host any composable children. This reproduces the
 * connected shape treatment without depending on that API.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    label: @Composable (T) -> Unit,
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            FilledTonalButton(
                onClick = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .height(height),
                shape = MaterialTheme.shapes.small,
                colors = if (isSelected) {
                    androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                label(option)
            }
        }
    }
}

/** Equal-width button row used for the three-way permission reply. */
@Composable
fun EqualWidthRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let {
            VerticalGap(6)
            it()
        }
    }
}
