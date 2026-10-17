package io.github.nezjusz.codedroid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host splices tool catalogues and reminders into the message it stores, wrapped in
 * `<system-reminder>` / `<system-update>`. Rendering that verbatim dumped pages of tool schemas
 * into the middle of a conversation, which looked like the agent had broken out of the app.
 *
 * The shapes below are copied from a real injected turn.
 */
class InjectedBlockTest {

    @Test
    fun `a reminder block is removed`() {
        val raw = "make a folder\n<system-reminder>do not mention this</system-reminder>"
        assertEquals("make a folder", stripInjectedBlocks(raw))
    }

    @Test
    fun `a closed system-update is removed`() {
        val raw = "hi\n<system-update>the catalog has changed</system-update>"
        assertEquals("hi", stripInjectedBlocks(raw))
    }

    @Test
    fun `an unclosed update running to the end is removed`() {
        // Updates are often appended to the turn with no closing tag.
        val raw = "hi\n<system-update>\nThe Code Mode tool catalog has changed."
        assertEquals("hi", stripInjectedBlocks(raw))
    }

    @Test
    fun `a multi-line catalogue is removed whole`() {
        val raw = """
            The catalog has changed.

            <system-update>
            # Code Mode
            - search({query?: string})
            - tools.opencode.models()
            </system-update>
        """.trimIndent()
        val out = stripInjectedBlocks(raw)
        assertTrue("leaked: $out", !out.contains("Code Mode"))
        assertTrue(out.contains("The catalog has changed."))
    }

    @Test
    fun `text after a closed block survives`() {
        val raw = "<system-reminder>x</system-reminder>\nthe actual answer"
        assertEquals("the actual answer", stripInjectedBlocks(raw))
    }

    @Test
    fun `two blocks are both removed`() {
        val raw = "a<system-reminder>1</system-reminder>b<system-update>2</system-update>c"
        assertEquals("abc", stripInjectedBlocks(raw))
    }

    @Test
    fun `ordinary text is untouched`() {
        assertEquals("just a prompt", stripInjectedBlocks("just a prompt"))
    }

    @Test
    fun `an empty message stays empty`() {
        assertEquals("", stripInjectedBlocks(""))
    }

    @Test
    fun `a message that is only an injection becomes empty`() {
        // Better an empty bubble than a page of tool schemas; the strip must not fail on this.
        assertEquals("", stripInjectedBlocks("<system-update>catalog</system-update>"))
    }

    @Test
    fun `html-looking user text is not eaten`() {
        // Only the known tags are stripped. A prompt about tags must survive intact.
        val raw = "compare <b>bold</b> with <system>system</system>"
        assertEquals(raw, stripInjectedBlocks(raw))
    }

    @Test
    fun `a code fence mentioning the tag is still stripped`() {
        // Deliberate: leaking the catalogue is worse than dropping a fence that mentions it.
        val raw = "look: <system-reminder>inside</system-reminder>"
        assertEquals("look:", stripInjectedBlocks(raw))
    }
}