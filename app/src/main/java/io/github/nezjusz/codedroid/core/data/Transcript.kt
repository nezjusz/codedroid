package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.MessageInfo
import io.github.nezjusz.codedroid.core.net.ModelRef
import io.github.nezjusz.codedroid.core.net.OcEvent
import io.github.nezjusz.codedroid.core.net.StructuredError
import io.github.nezjusz.codedroid.core.net.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * UI-facing transcript model.
 *
 * The wire format is a discriminated union that arrives partially: history comes whole from
 * `GET /message`, while a live turn arrives as a stream of deltas that only carry an
 * `assistantMessageID`. Normalising both into [Entry]/[Part] lets the same list drive initial
 * render and streaming updates.
 */
sealed interface Entry {
    val id: String
    val time: Long

    data class User(override val id: String, val text: String, override val time: Long) : Entry

    data class System(override val id: String, val text: String, override val time: Long) : Entry

    data class Assistant(
        override val id: String,
        val agent: String?,
        val model: ModelRef?,
        val parts: List<Part>,
        override val time: Long,
        val cost: Double = 0.0,
        val tokens: TokenUsage? = null,
        val error: StructuredError? = null,
        val finish: String? = null,
    ) : Entry {
        val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val busy: Boolean get() = finish == null
    }
}

sealed interface Part {
    data class Text(val text: String, val streaming: Boolean, val ordinal: Int = 0) : Part

    data class Reasoning(val text: String, val streaming: Boolean, val ordinal: Int = 0) : Part

    data class Tool(
        val id: String,
        val name: String,
        val status: String,
        val input: JsonObject?,
        val output: String,
        val error: String?,
        val startedAt: Long,
        val endedAt: Long?,
    ) : Part
}

/**
 * Accumulates one session's transcript.
 *
 * Not thread-safe on its own; all mutation goes through the owning repository on a single
 * dispatcher.
 */
class Transcript {

    private val entries = LinkedHashMap<String, Entry>()
    private val parts = HashMap<String, MutableList<Part>>()

    var sessionID: String? = null
        private set

    val ordered: List<Entry> get() = entries.values.sortedBy { it.time }

    fun clear(sessionID: String) {
        this.sessionID = sessionID
        entries.clear()
        parts.clear()
    }

    /** Seed from a full history fetch, preserving any in-flight streaming parts already known. */
    fun seed(sessionID: String, messages: List<MessageInfo>) {
        if (this.sessionID != sessionID) {
            clear(sessionID)
        }
        for (message in messages) applyMessage(message)
    }

    fun apply(event: OcEvent): Boolean {
        if (sessionID == null) return false
        return when (event) {
            // Guard on the event's own session: the repository fans one stream out to every open
            // transcript, so a missing check here would bleed one session's text into another.
            is OcEvent.TextDelta ->
                editText(event.sessionID, event.messageID, event.ordinal, delta = event.delta, reasoning = false)

            is OcEvent.TextEnded ->
                editText(event.sessionID, event.messageID, event.ordinal, delta = null, text = event.text, reasoning = false)

            is OcEvent.ReasoningDelta ->
                editText(event.sessionID, event.messageID, event.ordinal, delta = event.delta, reasoning = true)

            is OcEvent.ToolCalled -> editTool(
                sid = event.sessionID,
                messageID = event.messageID,
                toolID = event.toolID,
                name = event.name,
                input = event.input,
                status = "running",
                output = null,
                error = null,
            )

            is OcEvent.ToolProgress ->
                markTool(event.sessionID, event.messageID, event.toolID) { it.copy(status = "running") }

            is OcEvent.ToolSettled -> editTool(
                sid = event.sessionID,
                messageID = event.messageID,
                toolID = event.toolID,
                name = null,
                input = event.state.input,
                status = event.state.status,
                output = event.state.content.joinToString("\n") { it.text ?: it.uri.orEmpty() }.takeIf { it.isNotBlank() },
                error = event.state.error?.message ?: event.state.error?.type,
            )

            else -> false
        }
    }

    /** Append an optimistic local echo of a prompt that was just sent. */
    fun addOptimisticUser(text: String, id: String, time: Long) {
        entries.remove(id)
        entries[id] = Entry.User(id, text, time)
    }
    private fun applyMessage(message: MessageInfo) {
        val time = message.time?.created ?: 0
        when (message.type) {
            "user", "synthetic" -> {
                val text = message.text ?: message.content.filter { it.type == "text" }
                    .joinToString("\n") { it.text.orEmpty() }
                entries[message.id] = Entry.User(message.id, text, time)
                parts.remove(message.id)
            }

            "system" -> {
                entries[message.id] = Entry.System(message.id, message.text.orEmpty(), time)
                parts.remove(message.id)
            }

            "assistant" -> {
                val built = ArrayList<Part>(message.content.size)
                for (part in message.content) {
                    when (part.type) {
                        "text" -> built += Part.Text(part.text.orEmpty(), streaming = false, ordinal = built.size)
                        "reasoning" -> built += Part.Reasoning(part.text.orEmpty(), streaming = false, ordinal = built.size)
                        "tool" -> {
                            val state = part.state
                            val toolID = part.id ?: "${message.id}:${built.size}"
                            built += Part.Tool(
                                id = toolID,
                                name = part.name.orEmpty(),
                                status = state?.status ?: "pending",
                                input = state?.input,
                                output = state?.content?.joinToString("\n") { c -> c.text ?: c.uri.orEmpty() }
                                    ?.takeIf { it.isNotBlank() }.orEmpty(),
                                error = state?.error?.message,
                                startedAt = part.time?.created ?: time,
                                endedAt = part.time?.completed,
                            )
                        }
                    }
                }
                entries[message.id] = Entry.Assistant(
                    id = message.id,
                    agent = message.agent,
                    model = message.model,
                    parts = built,
                    time = time,
                    cost = message.cost ?: 0.0,
                    tokens = message.tokens,
                    error = message.error,
                    finish = message.finish,
                )
                parts[message.id] = built.toMutableList()
            }

            else -> Unit
        }
    }

    private fun assistantSlot(messageID: String, created: Long = nextTime()): MutableList<Part> {
        if (!entries.containsKey(messageID)) {
            entries[messageID] = Entry.Assistant(
                id = messageID,
                agent = null,
                model = null,
                parts = emptyList(),
                time = created,
            )
        }
        return parts.getOrPut(messageID) { mutableListOf() }
    }

    /**
     * Streaming frames carry no timestamp, so locally created entries are stamped just after the
     * newest entry already in the transcript. Using wall-clock time here would interleave wrongly
     * whenever the host and the phone disagree about the time.
     */
    private fun nextTime(): Long = (entries.values.maxOfOrNull { it.time } ?: 0L) + 1

    /**
     * Marks any still-open assistant turn as finished.
     *
     * Text and tool deltas never carry a finish reason, so without this a completed turn would
     * keep rendering its "thinking" indicator until the transcript was refetched.
     */
    fun markExecutionEnded(reason: String = "stop"): Boolean {
        var changed = false
        val ids = entries.values
            .filterIsInstance<Entry.Assistant>()
            .filter { it.busy }
            .map { it.id }
        for (id in ids) {
            val entry = entries[id]
            if (entry is Entry.Assistant) {
                entries[id] = entry.copy(finish = reason)
                changed = true
            }
        }
        return changed
    }

    private fun editText(
        sid: String,
        messageID: String,
        ordinal: Int,
        delta: String?,
        text: String? = null,
        reasoning: Boolean,
    ): Boolean {
        if (sid != sessionID || messageID.isEmpty()) return false
        val slot = assistantSlot(messageID)
        val index = slot.indexOfFirst {
            (it is Part.Text && !reasoning && it.ordinal == ordinal) ||
                (it is Part.Reasoning && reasoning && it.ordinal == ordinal)
        }
        if (index >= 0) {
            val existing = slot[index]
            slot[index] = when (existing) {
                is Part.Text -> existing.copy(
                    text = text ?: existing.text + delta.orEmpty(),
                    streaming = false,
                )

                is Part.Reasoning -> existing.copy(
                    text = text ?: existing.text + delta.orEmpty(),
                    streaming = false,
                )

                else -> existing
            }
        } else {
            val seed = text ?: delta.orEmpty()
            slot += if (reasoning) Part.Reasoning(seed, streaming = delta != null, ordinal = ordinal)
            else Part.Text(seed, streaming = delta != null, ordinal = ordinal)
        }
        publish(messageID)
        return true
    }

    private fun editTool(
        sid: String,
        messageID: String,
        toolID: String,
        name: String?,
        input: JsonObject?,
        status: String,
        output: String?,
        error: String?,
    ): Boolean {
        if (sid != sessionID || messageID.isEmpty() || toolID.isEmpty()) return false
        val slot = assistantSlot(messageID)
        val index = slot.indexOfFirst { it is Part.Tool && it.id == toolID }
        val now = nextTime()
        if (index >= 0) {
            val old = slot[index] as Part.Tool
            slot[index] = old.copy(
                name = name?.takeIf { it.isNotBlank() } ?: old.name,
                input = input ?: old.input,
                status = status,
                output = output ?: old.output,
                error = error ?: old.error,
                endedAt = if (status == "running") null else (old.endedAt ?: now),
            )
        } else {
            slot += Part.Tool(
                id = toolID,
                name = name.orEmpty(),
                status = status,
                input = input,
                output = output.orEmpty(),
                error = error,
                startedAt = now,
                endedAt = if (status == "running") null else now,
            )
        }
        publish(messageID)
        return true
    }

    private fun markTool(sid: String, messageID: String, toolID: String, block: (Part.Tool) -> Part.Tool): Boolean {
        if (sid != sessionID) return false
        val slot = parts[messageID] ?: return false
        val index = slot.indexOfFirst { it is Part.Tool && it.id == toolID }
        if (index < 0) return false
        slot[index] = block(slot[index] as Part.Tool)
        publish(messageID)
        return true
    }

    private fun publish(messageID: String) {
        val slot = parts[messageID] ?: return
        val previous = entries[messageID]
        if (previous is Entry.Assistant) {
            entries[messageID] = previous.copy(parts = slot.toList())
        }
    }
}

/** Compact, human-readable rendering of a tool call's JSON input. */
fun JsonObject.toolSummary(limit: Int = 240): String {
    if (isEmpty()) return ""
    val interesting = listOf(
        "command", "filePath", "path", "pattern", "url", "query", "prompt", "description", "subagent_type",
    )
    for (key in interesting) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (value.isString) {
            val text = value.content
            if (text.isNotBlank()) return text.take(limit) + if (text.length > limit) "…" else ""
        }
    }
    return toString().take(limit)
}
