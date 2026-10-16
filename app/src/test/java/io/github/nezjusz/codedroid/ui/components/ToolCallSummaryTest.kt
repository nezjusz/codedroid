package io.github.nezjusz.codedroid.ui.components

import io.github.nezjusz.codedroid.core.data.Part
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapsed header is the only thing visible until a reader opens a turn, so it has to say
 * what the turn did without being opened. "2 calls" alone does not distinguish an edit from a
 * build, and a list that runs off the end of the row has to be counted rather than truncated.
 */
class ToolCallSummaryTest {

    private fun tool(
        name: String,
        startedAt: Long = 0,
        endedAt: Long? = 10,
    ) = Part.Tool(
        id = "t_$name$startedAt",
        name = name,
        status = "completed",
        input = JsonObject(emptyMap()),
        output = "ok",
        error = null,
        startedAt = startedAt,
        endedAt = endedAt,
    )

    @Test
    fun `names the tools used`() {
        val label = summaryLabel(listOf(tool("edit"), tool("shell")))
        assertEquals("2 calls · edit, shell", label)
    }

    @Test
    fun `a single call reads in the singular`() {
        assertEquals("1 call · read", summaryLabel(listOf(tool("read"))))
    }

    @Test
    fun `timing is appended when there is any`() {
        val label = summaryLabel(listOf(tool("edit", 0, 2500)))
        assertTrue("expected timing, got: $label", label.contains("2.5s"))
    }

    @Test
    fun `sub-second runs carry no timing`() {
        val label = summaryLabel(listOf(tool("edit", 0, 400)))
        assertFalse("noise timing: $label", label.contains("s"))
    }

    @Test
    fun `a tool still running contributes no timing`() {
        val label = summaryLabel(listOf(tool("shell", 0, null)))
        assertEquals("1 call · shell", label)
    }

    @Test
    fun `repeated names collapse so five reads read as one`() {
        val label = summaryLabel(List(5) { tool("read") })
        assertEquals("5 calls · read", label)
    }

    @Test
    fun `the fourth distinct tool is counted, not dropped`() {
        val label = summaryLabel(listOf(tool("edit"), tool("shell"), tool("read"), tool("grep")))
        assertEquals("4 calls · edit, shell, read +1", label)
    }

    @Test
    fun `first-seen order is kept`() {
        val label = summaryLabel(listOf(tool("shell"), tool("edit"), tool("shell")))
        assertEquals("3 calls · shell, edit", label)
    }

    @Test
    fun `an empty list still summarises`() {
        assertEquals("0 calls", summaryLabel(emptyList()))
    }

    @Test
    fun `an unnamed tool does not leave a dangling separator`() {
        val label = summaryLabel(listOf(tool("")))
        assertEquals("1 call", label)
    }

    @Test
    fun `count, names and timing read together`() {
        val label = summaryLabel(
            listOf(tool("edit", 0, 1200), tool("shell", 0, 1800), tool("read", 0, 2000))
        )
        assertEquals("3 calls · edit, shell, read · 5.0s", label)
    }
}