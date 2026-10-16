package io.github.nezjusz.codedroid.core.net

import io.github.nezjusz.codedroid.core.data.Attachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the real upload → reference → prompt path against a running server.
 *
 * The interesting failure modes are all server-side contract details: the upload endpoint wants
 * bracket-notation location, the body is `application/octet-stream` rather than JSON, and the
 * prompt takes a `file://` reference rather than inline base64. Getting any of those wrong
 * produces a plausible-looking request that the host accepts and then ignores.
 */
class FileUploadTest {

    private val url: String? = System.getProperty("opencode.url")
    private val token: String? = System.getProperty("opencode.token")

    private fun client() = OpenCodeClient(url!!, token!!)

    private fun requireServer() {
        assumeTrue("no opencode.url configured", url != null)
        assumeTrue("no opencode.token configured", token != null)
    }

    @Test
    fun `uploading bytes writes them to the requested path`() {
        runBlocking {
            requireServer()
            val client = client()
            val dir = client.info().paths?.get("tmp")?.toString()?.trim('"') ?: "/tmp"
            val target = "$dir/codedroid-test-${System.nanoTime()}.txt"
            val payload = "attachment payload\nsecond line\n"

            val reported = mutableListOf<Pair<Long, Long>>()
            val written = client.uploadFile(
                directory = dir,
                path = target,
                content = payload.toByteArray(),
            ) { w, t -> reported += w to t }

            assertEquals("server echoes back the absolute path", target, written)

            assertTrue("progress must be reported", reported.isNotEmpty())
            val last = reported.last()
            assertEquals("final progress reaches 100%", payload.length.toLong(), last.first)
            assertEquals("total is the full size", payload.length.toLong(), last.second)

            // Progress must be monotonic; a bar that jumps backwards reads as a glitch.
            reported.zipWithNext { a, b ->
                assertTrue("progress went backwards: $reported", b.first >= a.first)
            }
        }
    }

    @Test
    fun `progress is throttled but reaches the end`() {
        runBlocking {
            requireServer()
            val client = client()
            val dir = client.info().paths?.get("tmp")?.toString()?.trim('"') ?: "/tmp"
            val payload = ByteArray(300 * 1024) { (it % 26).toByte() }

            val steps = mutableListOf<Long>()
            client.uploadFile(
                directory = dir,
                path = "$dir/codedroid-test-${System.nanoTime()}.bin",
                content = payload,
            ) { written, _ -> steps += written }

            assertEquals(payload.size.toLong(), steps.last())
            // 300 KiB at 64 KiB per step is ~5 callbacks, not 307200.
            assertTrue("expected throttled callbacks, got ${steps.size}", steps.size <= 12)
        }
    }

    @Test
    fun `an empty file uploads without stalling on progress`() {
        runBlocking {
            requireServer()
            val client = client()
            val dir = client.info().paths?.get("tmp")?.toString()?.trim('"') ?: "/tmp"

            val steps = mutableListOf<Long>()
            val written = client.uploadFile(
                directory = dir,
                path = "$dir/codedroid-test-${System.nanoTime()}.empty",
                content = ByteArray(0),
            ) { w, _ -> steps += w }

            assertTrue(written.endsWith(".empty"))
            assertTrue("progress must still be emitted", steps.isNotEmpty())
            assertEquals(0L, steps.last())
        }
    }

    @Test
    fun `a prompt with a file reference is accepted`() {
        runBlocking {
            requireServer()
            val client = client()
            val dir = client.info().paths?.get("tmp")?.toString()?.trim('"') ?: "/tmp"
            val target = "$dir/codedroid-test-${System.nanoTime()}.csv"
            client.uploadFile(directory = dir, path = target, content = "a,b\n1,2\n".toByteArray())

            val session = client.createSession(dir, title = "upload test")
            try {
                val body = client.prompt(
                    sessionID = session.id,
                    text = "Read the attached csv.",
                    files = listOf(
                        FileAttachment(uri = "file://$target", name = target.substringAfterLast('/'))
                    ),
                )
                assertNotNull(body)
            } finally {
                client.deleteSession(session.id)
            }
        }
    }

    @Test
    fun `attachment file names cannot escape the upload directory`() {
        runBlocking {
            requireServer()
            val client = client()
            val dir = client.info().paths?.get("tmp")?.toString()?.trim('"') ?: "/tmp"

            // Exercise the real sanitiser, not a copy of it.
            val hostile = Attachment(
                id = "att_test",
                displayName = "../../etc/codedroid-escape.txt",
                mimeType = "text/plain",
                sizeBytes = 1,
                content = ByteArray(1),
            )
            val safe = hostile.sanitiseFileName(hostile.displayName)
            val written = client.uploadFile(
                directory = dir,
                path = "$dir/codedroid-$safe",
                content = "x".toByteArray(),
            )

            assertTrue("path traversal survived sanitisation: $written", !written.contains(".."))
            assertTrue("write escaped the directory: $written", written.startsWith("$dir/"))
            assertEquals(dir, written.substringBeforeLast('/'))
        }
    }

    @Test
    fun `sanitiser handles hostile and degenerate names`() {
        fun attachment(name: String) = Attachment(
            id = "a", displayName = name, mimeType = "text/plain", sizeBytes = 0, content = ByteArray(0)
        )

        assertEquals("etc_passwd", attachment("/etc/passwd").sanitiseFileName("/etc/passwd"))
        assertEquals("a_b", attachment("a b").sanitiseFileName("a b"))
        assertEquals("attachment", attachment("").sanitiseFileName(""))
        assertEquals("attachment", attachment("   ").sanitiseFileName("   "))
        assertEquals("attachment", attachment("...").sanitiseFileName("..."))
        assertTrue(attachment("x".repeat(500)).sanitiseFileName("x".repeat(500)).length <= 80)
    }
}
