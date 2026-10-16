package io.github.nezjusz.codedroid.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rendering faults that turned a normal answer into an unreadable wall of prose.
 *
 * 1. A lone newline inside a paragraph was folded into a space. CommonMark does that by design,
 *    for prose. A model transcript is not prose: the newline is how the answer was laid out.
 * 2. Some hosts deliver the body with its line breaks escaped, so a paragraph break arrives as the
 *    two characters backslash and `n` and prints literally between the sentences.
 *
 * Both fixes can silently corrupt content - unescaping a regex, eating a Windows path - so the
 * cases where they must *not* act are pinned here alongside the ones where they must.
 */
class MarkdownLineBreaksTest {

    private fun plain(source: String) = Markdown.parse(source).plainText()

    @Test
    fun `a lone newline inside a paragraph becomes a line break`() {
        assertEquals("first line\nsecond line", plain("first line\nsecond line"))
    }

    @Test
    fun `adjacent paragraphs do not run together`() {
        // Preview text keeps a single break between blocks; a blank line would be noise here.
        assertEquals("one\ntwo", plain("one\n\ntwo"))
    }

    @Test
    fun `an escaped line break is restored`() {
        assertEquals("sentence one.\nsentence two.", plain("sentence one.\\nsentence two."))
    }

    @Test
    fun `a doubled escaped line break becomes a blank line`() {
        assertEquals("one.\n\ntwo.", plain("one.\\n\\ntwo."))
    }

    @Test
    fun `a fully escaped message reads as its own lines again`() {
        // The shape the phone actually showed: every line break in the body arrived escaped.
        val escaped = "**Fixed** the thing.\\nIt now works.\\n\\nNext: the other thing."
        val out = plain(escaped)
        assertTrue("expected real newlines, got: $out", out.contains("It now works.\n"))
        assertTrue("expected a paragraph break, got: $out", out.contains("works.\n\n"))
    }

    @Test
    fun `inline code in an escaped message is not rewritten`() {
        // The message as a whole is escaped, but a code span still means its characters literally.
        val out = plain("call `replace(\\n)` first")
        assertTrue("inline code was corrupted: $out", out.contains("\\n"))
    }

    @Test
    fun `a windows path in ordinary prose survives`() {
        // The source has real newlines, so it was never escaped and nothing is touched - which is
        // why the decision is made per message rather than per backslash.
        val out = plain("build under C:\\new\\table\nthen run it")
        assertTrue("path was corrupted: $out", out.contains("C:\\new\\table"))
    }

    @Test
    fun `escaped newlines inside a fenced block are left alone`() {
        // A code sample showing JSON must keep its escapes.
        val out = plain("see below\n\n```json\n{\"note\": \"a\\nb\"}\n```")
        assertTrue("code block was corrupted: $out", out.contains("a\\nb"))
    }

    @Test
    fun `an ordinary backslash is untouched`() {
        assertEquals("regex \\d+ matches digits", plain("regex \\d+ matches digits"))
    }

    @Test
    fun `list items keep their breaks`() {
        val out = plain("- first\n- second\n- third")
        assertTrue("list collapsed: $out", out.contains("first\nsecond"))
    }

    @Test
    fun `inline markup is stripped rather than shown`() {
        // Regression guard for plainText walking only the first child of each node.
        assertEquals("bold and italic and code", plain("**bold** and *italic* and `code`"))
    }

    @Test
    fun `a fenced block is not dropped from plain text`() {
        val out = plain("intro\n\n```\nblock body\n```")
        assertTrue("code block vanished: $out", out.contains("block body"))
    }
}