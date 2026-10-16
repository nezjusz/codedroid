package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.MessageInfo
import io.github.nezjusz.codedroid.core.net.MessageTime
import io.github.nezjusz.codedroid.core.net.ModelInfo
import io.github.nezjusz.codedroid.core.net.ModelLimit
import io.github.nezjusz.codedroid.core.net.ModelRef
import io.github.nezjusz.codedroid.core.net.TokenUsage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two problems a screenshot of a long session exposed.
 *
 * 1. An agent turn is a run of tool-only messages. Rendered one row each, the transcript filled
 *    with identical "Tool calls - 1 call" rows and read as broken.
 * 2. `GET /message` with `order=asc` and a limit returns the *oldest* N, so on a long session
 *    the newest turns were never fetched and the phone looked frozen.
 */
class TranscriptPresentationTest {

    private fun toolCall(id: String, name: String = "edit") = Part.Tool(
        id = id,
        name = name,
        status = "completed",
        input = null,
        output = "ok",
        error = null,
        startedAt = 0,
        endedAt = 10,
    )

    private fun assistant(
        id: String,
        parts: List<Part>,
        cost: Double = 0.0,
        tokens: TokenUsage? = null,
    ) = Entry.Assistant(
        id = id,
        agent = "build",
        model = null,
        parts = parts,
        time = 0,
        cost = cost,
        tokens = tokens,
        finish = "stop",
    )

    @Test
    fun `adjacent tool-only turns merge into one disclosure`() {
        val merged = listOf(
            assistant("a", listOf(Part.Text("Doing the thing", false))),
            assistant("b", listOf(toolCall("t1"))),
            assistant("c", listOf(toolCall("t2"))),
            assistant("d", listOf(toolCall("t3"))),
        ).mergeToolOnlyTurns()

        assertEquals("prose entry survives on its own", 2, merged.size)
        val tools = (merged[1] as Entry.Assistant).parts.filterIsInstance<Part.Tool>()
        assertEquals("all three tool calls in one section", 3, tools.size)
    }

    @Test
    fun `turns with prose are never merged`() {
        val merged = listOf(
            assistant("a", listOf(Part.Text("One", false))),
            assistant("b", listOf(Part.Text("Two", false))),
        ).mergeToolOnlyTurns()

        assertEquals(2, merged.size)
    }

    @Test
    fun `reasoning alone does not stop a merge`() {
        // Reasoning is collapsible metadata; two tool-only turns that happen to carry it should
        // still collapse, otherwise the row reappears whenever a tool call includes a think step.
        val merged = listOf(
            assistant("a", listOf(Part.Text("Working", false), toolCall("t1"))),
            assistant("b", listOf(Part.Reasoning("hmm", false), toolCall("t2"))),
        ).mergeToolOnlyTurns()

        assertEquals(2, merged.size)
    }

    @Test
    fun `merging sums cost and tokens so the totals stay honest`() {
        val merged = listOf(
            assistant("a", listOf(toolCall("t1")), cost = 0.5, tokens = TokenUsage(input = 10, output = 20)),
            assistant("b", listOf(toolCall("t2")), cost = 0.25, tokens = TokenUsage(input = 1, output = 2)),
        ).mergeToolOnlyTurns()

        assertEquals(1, merged.size)
        val entry = merged.single() as Entry.Assistant
        assertEquals(0.75, entry.cost, 0.0001)
        assertEquals(11L, entry.tokens?.input)
        assertEquals(22L, entry.tokens?.output)
    }

    @Test
    fun `a single tool-only turn is left alone`() {
        val input = listOf(assistant("a", listOf(toolCall("t1"))))
        assertEquals(input, input.mergeToolOnlyTurns())
    }

    @Test
    fun `merging never reorders or drops non-assistant entries`() {
        val input = listOf(
            Entry.User("u1", "hi", 1),
            assistant("a", listOf(toolCall("t1"))),
            assistant("b", listOf(toolCall("t2"))),
            Entry.User("u2", "again", 2),
        )
        val merged = input.mergeToolOnlyTurns()

        assertEquals(listOf("u1", "a", "u2"), merged.map { it.id })
    }

    @Test
    fun `empty transcript merges to empty`() {
        assertTrue(emptyList<Entry>().mergeToolOnlyTurns().isEmpty())
    }

    @Test
    fun `descending payload is reversed into chronological order`() {
        // The client asks for order=desc, so the server hands back newest-first; reversing is
        // what puts the transcript the right way up for display.
        val json = """{"data":[
            {"id":"msg_2","type":"user","time":{"created":2},"text":"second"},
            {"id":"msg_1","type":"user","time":{"created":1},"text":"first"}
        ],"cursor":{}}"""

        val served = io.github.nezjusz.codedroid.core.net.OpenCodeJson
            .decodeFromString<io.github.nezjusz.codedroid.core.net.MessagesResponse>(json)

        assertEquals(listOf("msg_2", "msg_1"), served.data.map { it.id })
        assertEquals(listOf("msg_1", "msg_2"), served.data.reversed().map { it.id })
    }

    @Test
    fun `model context limit decodes from the host`() {
        val json = """{"id":"space-bunny-free","modelID":"space-bunny-free",
            "providerID":"opencode","name":"Space Bunny","enabled":true,
            "limit":{"context":1048576,"input":524288,"output":524288}}"""
        val model = io.github.nezjusz.codedroid.core.net.OpenCodeJson.decodeFromString<ModelInfo>(json)

        assertEquals(1_048_576L, model.limit?.context)
        assertEquals(524_288L, model.limit?.output)
    }

    @Test
    fun `models without a limit decode rather than throwing`() {
        val json = """{"id":"m","modelID":"m","providerID":"p","name":"M","enabled":true}"""
        val model = io.github.nezjusz.codedroid.core.net.OpenCodeJson.decodeFromString<ModelInfo>(json)
        assertEquals(null, model.limit)
    }

    @Test
    fun `context used prefers the newest message that reports tokens`() {
        val messages = listOf(
            MessageInfo(
                id = "m1", type = "assistant", time = MessageTime(1),
                tokens = TokenUsage(input = 100),
            ),
            MessageInfo(
                id = "m2", type = "user", time = MessageTime(2),
            ),
            MessageInfo(
                id = "m3", type = "assistant", time = MessageTime(3),
                tokens = TokenUsage(
                    input = 400,
                    cache = JsonObject(mapOf("read" to kotlinx.serialization.json.JsonPrimitive(448_256))),
                ),
            ),
        )
        val newest = messages.lastOrNull { it.tokens != null }
        val used = (newest!!.tokens!!.input +
            (newest.tokens!!.cache?.get("read") as kotlinx.serialization.json.JsonPrimitive).content.toLong())

        assertEquals(448_656L, used)
        assertEquals(0.428f, used / 1_048_576f, 0.001f)
    }

    @Test
    fun `unknown context ceiling hides the indicator rather than claiming zero`() {
        assertEquals(0f, io.github.nezjusz.codedroid.core.data.contextFraction(0L, 0L), 0f)
        assertEquals(0f, io.github.nezjusz.codedroid.core.data.contextFraction(500L, -1L), 0f)
    }

    @Test
    fun `context fraction clamps above one when usage overshoots the ceiling`() {
        // A stale limit after a model swap would otherwise render a bar past its own track.
        assertEquals(1f, io.github.nezjusz.codedroid.core.data.contextFraction(2_000_000L, 1_048_576L), 0f)
    }

    @Test
    fun `context fraction tracks real usage against the reported ceiling`() {
        assertEquals(
            0.428f,
            io.github.nezjusz.codedroid.core.data.contextFraction(448_656L, 1_048_576L),
            0.001f,
        )
    }

    @Test
    fun `model reference carries the provider half needed for lookup`() {
        val ref = ModelRef(id = "space-bunny-free", providerID = "opencode")
        assertEquals("opencode/space-bunny-free", "${ref.providerID}/${ref.id}")
    }

    @Test
    fun `model limit with no context is treated as unknown`() {
        val partial = ModelLimit(context = 0, output = 100)
        assertEquals(null, partial.context.takeIf { it > 0 })
    }
}
