package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.FileAttachment
import java.util.Locale

private const val MAX_NAME_LENGTH = 80

/** Where an attachment is in its journey from the phone to the host. */
sealed interface AttachmentState {
    data object Pending : AttachmentState
    data class Uploading(val written: Long, val total: Long) : AttachmentState {
        val fraction: Float
            get() = if (total <= 0) 0f else (written.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Uploaded(val hostPath: String) : AttachmentState
    data class Failed(val message: String) : AttachmentState
}

/**
 * A file chosen on the phone and bound for a prompt.
 *
 * The bytes are held until send time rather than uploaded on pick: picking several files should
 * not immediately start pushing them at the network, and a prompt can still be edited in between.
 */
class Attachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    internal val content: ByteArray,
    val state: AttachmentState = AttachmentState.Pending,
) {
    val readableSize: String
        get() = when {
            sizeBytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format(Locale.US, "%.0f KB", sizeBytes / 1_000.0)
            else -> "$sizeBytes B"
        }

    val uploading: Boolean get() = state is AttachmentState.Uploading
    val uploaded: Boolean get() = state is AttachmentState.Uploaded

    internal fun withState(next: AttachmentState) = Attachment(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        content = content,
        state = next,
    )

    /**
     * Turns a user-supplied file name into something safe to append to a host path.
     *
     * Separators are replaced so a name cannot escape the target directory, and dot runs are
     * collapsed so a name cannot read as `..` to anything downstream.
     */
    fun sanitiseFileName(raw: String): String {
        val replaced = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dedotted = replaced.replace(Regex("\\.{2,}"), ".")
        return dedotted.trim('.', ' ', '_')
            .takeIf { it.isNotBlank() }
            ?.take(MAX_NAME_LENGTH)
            ?: "attachment"
    }

    /** Reference to send with the prompt once this file is on the host. */
    fun toFileAttachment(): FileAttachment? {
        val path = (state as? AttachmentState.Uploaded)?.hostPath ?: return null
        return FileAttachment(
            uri = "file://$path",
            name = displayName,
            description = "Attached from the CodeDroid app (${readableSize})",
        )
    }
}
