package io.github.nezjusz.codedroid.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nezjusz.codedroid.core.data.Attachment
import io.github.nezjusz.codedroid.core.data.AttachmentState
import io.github.nezjusz.codedroid.ui.components.LoadingIndicator
import java.util.Locale

/**
 * Attachment tray above the composer.
 *
 * Each file carries its own progress bar rather than one aggregate bar: uploads run
 * concurrently, so a single bar would jump around as different files finish and would not tell
 * you which one is still going.
 */
@Composable
fun AttachmentTray(
    attachments: List<Attachment>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentRow(attachment = attachment, enabled = enabled, onRemove = onRemove)
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    enabled: Boolean,
    onRemove: (String) -> Unit,
) {
    val state = attachment.state
    val failed = state is AttachmentState.Failed
    val border = when {
        failed -> MaterialTheme.colorScheme.error
        attachment.uploaded -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    failed -> Icons.Filled.Error
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = attachment.readableSize,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { onRemove(attachment.id) },
                enabled = enabled,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${attachment.displayName}",
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        when (state) {
            is AttachmentState.Uploading -> {
                val fraction by animateFloatAsState(state.fraction, label = "upload")
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Text(
                    text = "${(fraction * 100).toInt()}% · ${formatBytes(state.written)} of ${formatBytes(state.total)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is AttachmentState.Uploaded -> Text(
                text = "On the host",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            is AttachmentState.Failed -> Text(
                text = state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            AttachmentState.Pending -> Unit
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Reads a picked [Uri] into memory.
 *
 * Returns null when the file is unreadable or over [maxBytes]; the cap matters because the bytes
 * are buffered in RAM and then base64'd into a prompt, so a huge file would be a good way to kill
 * the app rather than to send it.
 */
fun readAttachment(context: Context, uri: Uri, maxBytes: Long): Attachment? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"

    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    var size = 0L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }

    val bytes = resolver.openInputStream(uri)?.use { input ->
        // Read one byte past the cap so an unknown-size file is rejected rather than truncated.
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
    } ?: return null

    Attachment(
        id = "att_${System.nanoTime()}",
        displayName = name,
        mimeType = mime,
        sizeBytes = if (size > 0) size else bytes.size.toLong(),
        content = bytes,
    )
}.getOrNull()
