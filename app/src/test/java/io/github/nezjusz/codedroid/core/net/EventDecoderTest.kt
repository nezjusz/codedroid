package io.github.nezjusz.codedroid.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression test against real payloads captured from a live opencode server.
 *
 * The fixtures in `sse-fixtures.json` are verbatim `data:` frames from a v2 server, one per event
 * type. Hand-written SSE parsing and a hand-written union decoder are exactly the kind of code
 * that silently degrades when the server changes, so these are pinned to observed traffic rather
 * than to assumptions.
 */
class EventDecoderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixtures(): Map<String, String> {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("sse-fixtures.json")) {
            "sse-fixtures.json missing"
        }
        return stream.bufferedReader().use { json.decodeFromString<Map<String, String>>(it.readText()) }
    }

    private fun client() = OpenCodeClient("http://127.0.0.1:4096", token = "unused")

    @Test
    fun `every observed event type decodes without throwing`() {
        val fixtures = fixtures()
        assertTrue("expected a meaningful fixture set", fixtures.size >= 20)

        val failures = mutableListOf<String>()
        for ((type, payload) in fixtures) {
            try {
                client().decodeEvent(payload)
            } catch (e: Exception) {
                failures += "$type -> ${e::class.simpleName}: ${e.message}"
            }
        }
        assertEquals("decoders threw", emptyList<String>(), failures)
    }

    @Test
    fun `server connected`() {
        val event = client().decodeEvent(fixtures().getValue("server.connected"))
        assertEquals(OcEvent.ServerConnected, event)
    }

    @Test
    fun `text delta carries session, message, ordinal and delta`() {
        val event = client().decodeEvent(fixtures().getValue("session.text.delta"))
        val delta = event as OcEvent.TextDelta
        assertTrue(delta.sessionID.startsWith("ses_"))
        assertTrue(delta.messageID.startsWith("msg_"))
        assertTrue(delta.delta.isNotEmpty())
    }

    @Test
    fun `reasoning delta is distinct from text delta`() {
        val event = client().decodeEvent(fixtures().getValue("session.reasoning.delta"))
        assertTrue(event is OcEvent.ReasoningDelta)
    }

    @Test
    fun `tool name and tool input arrive on separate frames and both map to ToolCalled`() {
        val fixtures = fixtures()

        // Real server behaviour: `tool.input.started` carries the name, `tool.called` carries the args.
        val named = client().decodeEvent(fixtures.getValue("session.tool.input.started")) as OcEvent.ToolCalled
        assertNotNull("tool name should come from input.started", named.name)
        assertTrue(named.toolID.isNotBlank())

        val called = client().decodeEvent(fixtures.getValue("session.tool.called")) as OcEvent.ToolCalled
        assertNotNull("tool input should parse into a JsonObject", called.input)
        assertEquals("tool.called must not invent a name", null, called.name)
        assertEquals("both frames describe the same tool", named.toolID, called.toolID)
    }

    @Test
    fun `tool success carries decoded text content`() {
        val event = client().decodeEvent(fixtures().getValue("session.tool.success"))
        val settled = event as OcEvent.ToolSettled
        assertEquals("completed", settled.state.status)
        assertTrue(settled.state.content.isNotEmpty())
    }

    @Test
    fun `tool failed is surfaced as an error state`() {
        val fixtures = fixtures()
        assumeTrue(fixtures.containsKey("session.tool.failed"))
        val event = client().decodeEvent(fixtures.getValue("session.tool.failed"))
        val settled = event as OcEvent.ToolSettled
        assertEquals("error", settled.state.status)
    }

    @Test
    fun `execution succeeded maps to the completed phase`() {
        val event = client().decodeEvent(fixtures().getValue("session.execution.succeeded"))
        val execution = event as OcEvent.Execution
        assertEquals("succeeded", execution.phase)
        assertTrue(execution.sessionID.startsWith("ses_"))
    }

    @Test
    fun `usage update maps cost and tokens`() {
        val event = client().decodeEvent(fixtures().getValue("session.usage.updated"))
        val usage = event as OcEvent.Usage
        assertTrue(usage.tokens.input >= 0)
        assertTrue(usage.cost >= 0.0)
    }

    @Test
    fun `session created yields a partial session that the repository merge can complete`() {
        val event = client().decodeEvent(fixtures().getValue("session.created"))
        val upsert = event as OcEvent.SessionUpsert
        assertTrue(upsert.session.id.startsWith("ses_"))
        // These are absent in the payload; the merge path relies on them being null, not zero.
        assertEquals(null, upsert.session.tokens)
        assertEquals(0.0, upsert.session.cost, 0.0)
    }

    @Test
    fun `session deleted carries only the id`() {
        val event = client().decodeEvent(fixtures().getValue("session.deleted"))
        assertTrue((event as OcEvent.SessionDeleted).sessionID.startsWith("ses_"))
    }

    @Test
    fun `project updated decodes the canonical path`() {
        val event = client().decodeEvent(fixtures().getValue("project.updated"))
        val project = (event as OcEvent.ProjectUpsert).project
        assertTrue(project.id.isNotBlank())
        assertTrue(project.canonical.startsWith("/"))
    }

    @Test
    fun `unrelated event types are ignored rather than crashing`() {
        listOf("shell.created", "shell.exited", "session.inbox.delivered", "provider.updated", "model.updated")
            .forEach { type ->
                val payload = fixtures().getValue(type)
                // These may decode to something or to null; the contract is "never throw".
                client().decodeEvent(payload)
            }
    }

    @Test
    fun `malformed frames do not throw`() {
        listOf("", "   ", "not json", "{}", "{\"type\":null}", "{\"data\":{}}", "[]").forEach { bad ->
            client().decodeEvent(bad)
        }
    }

    @Test
    fun `a permission asked frame decodes into a request`() {
        val frame = """
            {"id":"evt_1","type":"permission.asked","data":{
              "id":"prm_abc","sessionID":"ses_xyz","action":"read",
              "resources":["/home/user/.env"],"save":[],"message":"Read .env"}}
        """.trimIndent()
        val event = client().decodeEvent(frame)
        val request = (event as OcEvent.PermissionAsked).request
        assertEquals("prm_abc", request.id)
        assertEquals("ses_xyz", request.sessionID)
        assertEquals("Read file", request.title)
        assertEquals(listOf("/home/user/.env"), request.resources)
    }

    @Test
    fun `base url normalisation`() {
        assertEquals("http://10.0.0.5:4096", OpenCodeClient.normalizeBaseUrl("10.0.0.5:4096"))
        assertEquals("http://10.0.0.5:4096", OpenCodeClient.normalizeBaseUrl("http://10.0.0.5:4096/"))
        assertEquals("https://box.local", OpenCodeClient.normalizeBaseUrl("https://box.local"))
    }
}
