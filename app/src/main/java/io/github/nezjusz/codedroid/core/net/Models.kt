package io.github.nezjusz.codedroid.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire models for the opencode v2 HTTP API.
 *
 * Every endpoint response is wrapped in a `data` envelope, so responses are decoded into
 * [DataEnvelope]. Message shapes are intentionally flattened into a single [MessageInfo] with
 * nullable fields: the API returns a discriminated union keyed on `type`, and a flat model keeps
 * streaming updates cheap to merge without polymorphic serialization bookkeeping.
 */

@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class ServerInfo(
    val version: String = "",
    val pid: Int = 0,
    val urls: List<String> = emptyList(),
    val paths: JsonObject? = null,
    val capabilities: JsonObject? = null,
)

@Serializable
data class PairingCode(val code: String, val expires_in: Int = 300)

@Serializable
data class PairingSession(val token: String)

@Serializable
data class Project(
    val id: String,
    val canonical: String = "",
    val name: String? = null,
    val vcs: String? = null,
    val time: ProjectTime? = null,
    val sandboxes: List<JsonObject> = emptyList(),
)

@Serializable
data class ProjectTime(
    val created: Long = 0,
    val updated: Long = 0,
    val active: Long = 0,
)

@Serializable
data class LocationRef(val directory: String = "", val worktree: String? = null)

@Serializable
data class ModelRef(
    val id: String = "",
    val providerID: String = "",
    val variant: String? = null,
) {
    val label: String get() = buildString {
        append(id)
        if (providerID.isNotEmpty()) append(" · ").append(providerID)
        if (!variant.isNullOrEmpty() && variant != "default") append(" · ").append(variant)
    }
}

@Serializable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: JsonObject? = null,
) {
    val total: Long get() = input + output + reasoning
}

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0,
    val idle: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class SessionInfo(
    val id: String,
    val projectID: String? = null,
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val cost: Double = 0.0,
    val tokens: TokenUsage? = null,
    val time: SessionTime = SessionTime(),
    val location: LocationRef? = null,
    val subpath: String? = null,
    val outcome: String? = null,
    val permissions: List<PermissionRule>? = null,
) {
    val directory: String? get() = location?.directory
    val isSubSession: Boolean get() = parentID != null
}

@Serializable
data class MessageTime(
    val created: Long = 0,
    val completed: Long? = null,
    val streamed: Long? = null,
)

@Serializable
data class StructuredError(
    val type: String = "",
    val message: String = "",
    val status: Int? = null,
)

@Serializable
data class ToolContent(
    val type: String = "text",
    val text: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolState(
    /** pending | running | completed | error */
    val status: String = "pending",
    val input: JsonObject? = null,
    val content: List<ToolContent> = emptyList(),
    val error: StructuredError? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ToolTime(
    val created: Long = 0,
    val ran: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessagePart(
    /** text | reasoning | tool */
    val type: String = "text",
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val state: ToolState? = null,
    val time: ToolTime? = null,
)

@Serializable
data class MessageInfo(
    val id: String,
    val type: String,
    val time: MessageTime? = null,
    // user / synthetic / shell
    val text: String? = null,
    // assistant
    val agent: String? = null,
    val model: ModelRef? = null,
    val content: List<MessagePart> = emptyList(),
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val error: StructuredError? = null,
    val retry: JsonObject? = null,
) {
    val isUser: Boolean get() = type == "user"
    val isAssistant: Boolean get() = type == "assistant"

    val preview: String
        get() = when (type) {
            "user" -> text.orEmpty()
            "assistant" -> content.filter { it.type == "text" }.joinToString("\n") { it.text.orEmpty() }
            "system" -> text.orEmpty()
            else -> content.firstOrNull { it.type == "text" }?.text
                ?: content.firstOrNull { it.type == "tool" }?.name.orEmpty()
        }
}

@Serializable
data class MessagesResponse(
    val data: List<MessageInfo> = emptyList(),
    val cursor: JsonObject? = null,
)

@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val source: JsonObject? = null,
    val message: String? = null,
) {
    val title: String
        get() = when (action.substringAfterLast('.')) {
            "read" -> "Read file"
            "write" -> "Write file"
            "edit" -> "Edit file"
            "bash" -> "Run command"
            "webfetch" -> "Fetch URL"
            else -> action.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                .ifEmpty { "Permission" }
        }
}

@Serializable
data class AgentInfo(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
)

@Serializable
data class ModelInfo(
    val id: String,
    val modelID: String = "",
    val providerID: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val limit: ModelLimit? = null,
)

/** Context and output ceilings for a model. */
@Serializable
data class ModelLimit(
    val context: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
)

@Serializable
data class AgentEnvelope(val location: LocationRef? = null, val data: List<AgentInfo> = emptyList())

@Serializable
data class ModelEnvelope(val location: LocationRef? = null, val data: List<ModelInfo> = emptyList())

@Serializable
data class PromptResult(
    val info: MessageInfo? = null,
    val parts: List<MessagePart> = emptyList(),
)

/** `POST /api/session/{id}/interrupt` response. */
@Serializable
data class InterruptResult(val interrupted: Boolean = false)

/** File reference sent alongside a prompt. */
@Serializable
data class FileAttachment(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)

/** `POST /api/experimental/fs/write` response. */
@Serializable
data class FileSystemWrite(val path: String)

/** One entry in a session's permission ruleset. */
@Serializable
data class PermissionRule(
    val action: String,
    val resource: String,
    /** allow | deny | ask */
    val effect: String,
)

/** `Permission.Ruleset` as returned on `Session.Info`. */
@Serializable
data class PermissionRuleset(val rules: List<PermissionRule> = emptyList())

/**
 * Decoded server-sent event.
 *
 * The SSE payload always carries `{id, type, data, created, location?}`; [data] is a free-form
 * object whose shape depends on [type], so events are decoded into this closed hierarchy.
 */
sealed interface OcEvent {
    data object ServerConnected : OcEvent

    data class SessionUpsert(val session: SessionInfo) : OcEvent
    data class SessionDeleted(val sessionID: String) : OcEvent

    data class TextDelta(
        val sessionID: String,
        val messageID: String,
        val ordinal: Int,
        val delta: String,
    ) : OcEvent

    data class TextEnded(
        val sessionID: String,
        val messageID: String,
        val ordinal: Int,
        val text: String,
    ) : OcEvent

    data class ReasoningDelta(
        val sessionID: String,
        val messageID: String,
        val ordinal: Int,
        val delta: String,
    ) : OcEvent

    data class ToolCalled(
        val sessionID: String,
        val messageID: String,
        val toolID: String,
        /** Only present on `tool.input.started`; the `tool.called` frame omits it. */
        val name: String?,
        val input: JsonObject?,
        val executed: Boolean,
    ) : OcEvent

    data class ToolSettled(
        val sessionID: String,
        val messageID: String,
        val toolID: String,
        val state: ToolState,
    ) : OcEvent

    data class ToolProgress(val sessionID: String, val messageID: String, val toolID: String) : OcEvent

    data class Execution(val sessionID: String, val phase: String, val error: StructuredError? = null) :
        OcEvent

    data class PermissionAsked(val request: PermissionRequest) : OcEvent
    data class PermissionSettled(val sessionID: String, val requestID: String, val reply: String) : OcEvent

    data class ProjectUpsert(val project: Project) : OcEvent
    data class Usage(val sessionID: String, val cost: Double, val tokens: TokenUsage) : OcEvent
}

/** Helpers for reading loosely-typed JSON event payloads. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || !it.toString().isBlank() }?.toString()?.trim('"')

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.dbl(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
