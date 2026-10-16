package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.OcEvent
import io.github.nezjusz.codedroid.core.net.OpenCodeClient
import io.github.nezjusz.codedroid.core.net.OpenCodeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a real 120-frame event sequence through the decoder and the transcript merger.
 *
 * Streaming is the part of this app most likely to break quietly: the transcript is rebuilt from
 * deltas that arrive out of order relative to the tool lifecycle, and no REST call ever returns
 * the same shape. This pins the merge behaviour against recorded traffic.
 */
class TranscriptTest {

    @Serializable
    private data class Sequence(
        val sessionID: String,
        val messageID: String,
        val events: List<Frame>,
    )

    @Serializable
    private data class Frame(val type: String, val data: JsonObject)

    private val client = OpenCodeClient("http://127.0.0.1:4096", token = "unused")
    private val json = Json { ignoreUnknownKeys = true }

    private fun sequence(): Sequence {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("transcript-sequence.json"))
        return stream.bufferedReader().use { json.decodeFromString(it.readText()) }
    }

    /** Rebuilds the SSE `data:` payload the decoder expects and decodes it. */
    private fun decode(frame: Frame): OcEvent? {
        val payload = buildJsonObject {
            put("type", frame.type)
            put("data", frame.data)
        }
        return client.decodeEvent(payload.toString())
    }

    @Test
    fun `replaying a real sequence produces one assistant message with merged parts`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        var applied = 0
        seq.events.forEach { frame ->
            val event = decode(frame)
            if (event != null && transcript.apply(event)) applied++
        }

        assertTrue("no frames were applied", applied > 50)

        val entries = transcript.ordered
        assertEquals("expected exactly one assistant entry", 1, entries.size)

        val assistant = entries.single() as Entry.Assistant
        assertEquals(seq.messageID, assistant.id)
    }

    @Test
    fun `reasoning deltas concatenate in arrival order`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        val expected = StringBuilder()
        seq.events.forEach { frame ->
            if (frame.type == "session.reasoning.delta") {
                expected.append(frame.data["delta"].toString().trim('"'))
            }
            decode(frame)?.let { transcript.apply(it) }
        }

        val reasoning = (transcript.ordered.single() as Entry.Assistant)
            .parts.filterIsInstance<Part.Reasoning>()

        assertEquals("reasoning should collapse into one part", 1, reasoning.size)
        assertEquals(expected.toString(), reasoning.single().text)
        assertFalse(reasoning.single().streaming)
    }

    @Test
    fun `text deltas are replaced by the authoritative text-ended payload`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        val ended = seq.events.last { it.type == "session.text.ended" }
        seq.events.forEach { frame ->
            decode(frame)?.let { transcript.apply(it) }
        }

        val text = (transcript.ordered.single() as Entry.Assistant)
            .parts.filterIsInstance<Part.Text>()

        assertEquals(1, text.size)
        assertEquals(ended.data["text"].toString().trim('"'), text.single().text)
        assertFalse("a finished part must not still be marked streaming", text.single().streaming)
    }

    @Test
    fun `each distinct tool becomes one card with a terminal status`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        val declaredTools = seq.events.filter { it.type == "session.tool.input.started" }
            .mapNotNull { (it.data["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .toSet()

        seq.events.forEach { frame -> decode(frame)?.let { transcript.apply(it) } }

        val tools = (transcript.ordered.single() as Entry.Assistant).parts.filterIsInstance<Part.Tool>()

        assertEquals("one card per tool, not per frame", declaredTools.size, tools.size)
        assertEquals("tool ids must be unique", declaredTools.size, tools.map { it.id }.toSet().size)

        tools.forEach { tool ->
            assertTrue(
                "tool ${tool.id} ended in '${tool.status}'",
                tool.status == "completed" || tool.status == "error",
            )
            assertTrue("tool ${tool.id} should carry a name", tool.name.isNotBlank())
        }
    }

    @Test
    fun `a tool that failed is marked as an error`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        val failedIds = seq.events.filter { it.type == "session.tool.failed" }
            .mapNotNull { (it.data["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .toSet()
        assume(failedIds.isNotEmpty())

        seq.events.forEach { frame -> decode(frame)?.let { transcript.apply(it) } }

        val tools = (transcript.ordered.single() as Entry.Assistant).parts.filterIsInstance<Part.Tool>()
        tools.filter { it.id in failedIds }.forEach {
            assertEquals("tool ${it.id} should be an error", "error", it.status)
        }
    }

    @Test
    fun `a tool that was called carries its parsed input`() {
        val seq = sequence()
        val transcript = Transcript()
        transcript.clear(seq.sessionID)

        seq.events.forEach { frame -> decode(frame)?.let { transcript.apply(it) } }

        val tools = (transcript.ordered.single() as Entry.Assistant).parts.filterIsInstance<Part.Tool>()
        assertTrue("at least one tool should have input", tools.any { it.input != null })
        assertNotNull(tools.first().startedAt)
    }

    @Test
    fun `optimistic local prompts appear in the transcript`() {
        val transcript = Transcript()
        transcript.clear("ses_test")
        transcript.addOptimisticUser("fix the build", "local_1", 1_000L)
        transcript.addOptimisticUser("and add tests", "local_2", 2_000L)

        val entries = transcript.ordered
        assertEquals(2, entries.size)
        assertEquals("fix the build", (entries[0] as Entry.User).text)
        assertEquals(listOf("local_1", "local_2"), entries.map { it.id })
    }

    @Test
    fun `history seed orders by message time`() {
        val transcript = Transcript()
        val messages = listOf(
            io.github.nezjusz.codedroid.core.net.MessageInfo(
                id = "msg_b", type = "user", text = "second", time = io.github.nezjusz.codedroid.core.net.MessageTime(2_000L)
            ),
            io.github.nezjusz.codedroid.core.net.MessageInfo(
                id = "msg_a", type = "user", text = "first", time = io.github.nezjusz.codedroid.core.net.MessageTime(1_000L)
            ),
        )
        transcript.seed("ses_test", messages)

        val entries = transcript.ordered
        assertEquals(listOf("msg_a", "msg_b"), entries.map { it.id })
        assertEquals("first", (entries.first() as Entry.User).text)
    }

    @Test
    fun `events for a different session are ignored`() {
        val transcript = Transcript()
        transcript.clear("ses_mine")
        val applied = transcript.apply(
            OcEvent.TextDelta("ses_other", "msg_x", 0, "hello")
        )
        assertFalse("cross-session events must not leak", applied)
        assertTrue(transcript.ordered.isEmpty())
    }

    @Test
    fun `a streamed turn stays busy until execution ends`() {
        val transcript = Transcript()
        transcript.clear("ses_test")

        transcript.apply(OcEvent.TextDelta("ses_test", "msg_a", 0, "Working"))
        transcript.apply(OcEvent.TextEnded("ses_test", "msg_a", 0, "Working on it"))

        val assistant = transcript.ordered.single() as Entry.Assistant
        assertTrue("delta frames alone cannot mark a turn finished", assistant.busy)

        assertTrue("marking should report a change", transcript.markExecutionEnded("stop"))
        assertFalse(
            "the turn should no longer render as busy",
            (transcript.ordered.single() as Entry.Assistant).busy,
        )
    }

    @Test
    fun `marking finished is a no-op when nothing is in flight`() {
        val transcript = Transcript()
        transcript.clear("ses_test")
        transcript.addOptimisticUser("hi", "local_1", 5L)
        assertFalse(transcript.markExecutionEnded("stop"))
    }

    @Test
    fun `streamed entries are ordered after seeded history regardless of clock skew`() {
        val transcript = Transcript()
        // History stamped far in the future, as if the host clock runs ahead of the phone.
        transcript.seed(
            "ses_test",
            listOf(
                io.github.nezjusz.codedroid.core.net.MessageInfo(
                    id = "msg_seed", type = "user", text = "seeded",
                    time = io.github.nezjusz.codedroid.core.net.MessageTime(9_999_999_999_999L),
                )
            )
        )
        transcript.apply(OcEvent.TextDelta("ses_test", "msg_live", 0, "streamed"))

        val ids = transcript.ordered.map { it.id }
        assertEquals("seeded message must stay first", listOf("msg_seed", "msg_live"), ids)
    }

    private fun assume(condition: Boolean) = org.junit.Assume.assumeTrue(condition)
}
