package io.github.nezjusz.codedroid.core.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a real opencode server.
 *
 * Skipped unless `-Dopencode.url` and `-Dopencode.token` are provided, so the suite stays green on
 * a machine with no host. This is where the hand-written SSE reader gets exercised against a real
 * socket, including heartbeat comments and event dispatch.
 */
class LiveServerTest {

    private val url: String? = System.getProperty("opencode.url")
    private val token: String? = System.getProperty("opencode.token")

    private fun client() = OpenCodeClient(url!!, token!!)

    private fun requireServer() {
        assumeTrue("no opencode.url configured", url != null)
        assumeTrue("no opencode.token configured", token != null)
    }

    @Test
    fun `server info decodes`() = runBlocking {
        requireServer()
        val info = client().info()
        assertTrue("version should look like a version", info.version.isNotBlank())
        assertTrue("pid should be positive", info.pid > 0)
    }

    @Test
    fun `projects decode`() = runBlocking {
        requireServer()
        val projects = client().projects()
        // A host with no projects yet is legitimate; the call must simply not throw.
        projects.forEach { assertTrue(it.id.isNotBlank()) }
    }

    @Test
    fun `single session is enveloped`() = runBlocking {
        requireServer()
        val sessions = client().sessions(limit = 1)
        assumeTrue("host has no sessions", sessions.isNotEmpty())
        // Regression: GET /api/session/{id} wraps the object in a `data` envelope.
        val session = client().session(sessions.first().id)
        assertEquals(sessions.first().id, session.id)
    }

    @Test
    fun `sessions decode with location and timestamps`() = runBlocking {
        requireServer()
        val sessions = client().sessions(limit = 5)
        sessions.forEach { session ->
            assertTrue(session.id.startsWith("ses_"))
            assertTrue("created should be set", session.time.created > 0)
            val dir = session.directory
            if (dir != null) assertTrue(dir.startsWith("/"))
        }
    }

    @Test
    fun `messages decode for a real session`() = runBlocking {
        requireServer()
        val sessions = client().sessions(limit = 5)
        assumeTrue("host has no sessions to read", sessions.isNotEmpty())
        val messages = client().messages(sessions.first().id)
        messages.forEach { message ->
            assertTrue(message.id.isNotBlank())
            assertTrue("type must be present", message.type.isNotBlank())
        }
    }

    @Test
    fun `messages come back oldest-first even when the session overflows the limit`() = runBlocking {
        // Regression: `order=asc` with a limit returns the OLDEST n, so on a session longer than
        // the limit the newest turns were never fetched and the chat screen looked frozen while
        // the agent was visibly working. This asserts the two properties that fix depends on:
        // chronological order, and that the tail is genuinely the newest message on the host.
        requireServer()
        val client = client()
        val sessions = client.sessions(limit = 5)
        assumeTrue("host has no sessions", sessions.isNotEmpty())
        val sessionID = sessions.first().id

        val page = client.messages(sessionID, limit = 5)
        assumeTrue("session too short to exercise paging", page.size >= 5)

        val times = page.mapNotNull { it.time?.created }
        if (times.size >= 2) {
            assertTrue(
                "messages must be chronological, got ${page.map { it.id }}",
                times.zipWithNext().all { (a, b) -> a <= b },
            )
        }

        // Ask the host for everything it will serve and confirm the limited page ends on the same
        // message. If the ordering regressed, this is where it shows up. The host rejects a
        // limit above 200, so that is as much as one request can return.
        val everything = client.messages(sessionID, limit = 200)
        assumeTrue("could not read the full session", everything.size > page.size)
        assertEquals(
            "a limited page must end on the newest message, not the oldest window of one",
            everything.last().id,
            page.last().id,
        )
    }

    @Test
    fun `agents and models decode`() = runBlocking {
        requireServer()
        client().agents(null)
        client().models(null)
        Unit
    }

    @Test
    fun `event stream connects and stays open`() = runBlocking {
        requireServer()
        val connected = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()

        val collector = async(Dispatchers.IO) {
            client().events().collect { event ->
                synchronized(seen) { seen += event::class.simpleName.orEmpty() }
                if (event is OcEvent.ServerConnected) connected.complete(Unit)
            }
        }

        withTimeoutOrNull(15_000) { connected.await() }
        val snapshot = synchronized(seen) { seen.toList() }
        assertTrue("server.connected never arrived; saw $snapshot", snapshot.contains("ServerConnected"))

        // Heartbeats are SSE comments and must not surface as events or kill the stream.
        assertFalse(snapshot.contains("null"))
        collector.cancel()
    }

    @Test
    fun `pairing code round trip issues a usable token`() = runBlocking {
        requireServer()

        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        // Step 1: mint a code on the host, as `opencode api post /api/pair` does.
        val pairRequest = Request.Builder()
            .url("${url!!.trimEnd('/')}/api/pair")
            .header("Authorization", Credentials.basic("opencode", token!!))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val pairBody = http.newCall(pairRequest).execute().use { response ->
            assertTrue("pair request failed with ${response.code}", response.isSuccessful)
            checkNotNull(response.body?.string())
        }
        val code = OpenCodeJson.decodeFromString<PairingCode>(pairBody).code

        // Step 2: redeem it unauthenticated, exactly as the phone does.
        val tokenFromPairing = OpenCodeClient(url!!, token = "").redeemPairingCode(code)
        assertTrue("token should be substantial", tokenFromPairing.length > 20)

        // Step 3: the new token must actually authenticate.
        val info = OpenCodeClient(url!!, tokenFromPairing).info()
        assertNotNull(info.version)

        // Step 4: codes are single use.
        val replay = runCatching { OpenCodeClient(url!!, token = "").redeemPairingCode(code) }
        assertTrue("pairing code should not be reusable", replay.isFailure)
    }
}
