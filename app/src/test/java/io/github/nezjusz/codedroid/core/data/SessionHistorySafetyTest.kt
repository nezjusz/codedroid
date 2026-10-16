package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.PermissionRule
import io.github.nezjusz.codedroid.core.net.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "my history vanished" failure.
 *
 * The session list was briefly rebuilt as `sessions ?: emptyList()`, so any failed fetch — a
 * timeout, an expired token, a flaky Wi-Fi hop — replaced a populated list with an empty one and
 * made it look as though the sessions had been deleted. They were never deleted; the phone had
 * simply thrown away what it already knew.
 *
 * These tests pin the two rules that prevent a recurrence: a failed fetch keeps the previous
 * value, and the visible list is never scoped by the "default directory for new chats" setting.
 */
class SessionHistorySafetyTest {

    private fun session(id: String, title: String = id, directory: String? = "/home/user/a") =
        SessionInfo(
            id = id,
            title = title,
            location = directory?.let { io.github.nezjusz.codedroid.core.net.LocationRef(it) },
            time = io.github.nezjusz.codedroid.core.net.SessionTime(created = 1L, updated = 1L),
        )

    /**
     * Mirrors the merge policy in OpenCodeRepository.reconcile: applied only when the fetch
     * returned something, always merged with what is already known, and blank fields never
     * overwrite a known value.
     */
    private fun applyFetch(
        current: List<SessionInfo>,
        fetched: List<SessionInfo>?,
    ): List<SessionInfo> {
        if (fetched == null) return current
        val known = current.associateBy { it.id }
        return fetched.map { fresh ->
            val old = known[fresh.id]
            if (old == null) {
                fresh
            } else {
                fresh.copy(
                    title = fresh.title?.takeIf { it.isNotBlank() } ?: old.title,
                    agent = fresh.agent ?: old.agent,
                    cost = if (fresh.cost != 0.0) fresh.cost else old.cost,
                )
            }
        }
    }

    @Test
    fun `a failed fetch leaves the existing session list untouched`() {
        val existing = listOf(session("ses_a", "Work"), session("ses_b", "Side project"))
        assertEquals(existing, applyFetch(existing, fetched = null))
    }

    @Test
    fun `a failed fetch on an empty list stays empty rather than throwing`() {
        assertTrue(applyFetch(emptyList(), fetched = null).isEmpty())
    }

    @Test
    fun `a successful fetch adds new sessions`() {
        val existing = listOf(session("ses_a"))
        val result = applyFetch(existing, listOf(session("ses_a"), session("ses_b")))
        assertEquals(listOf("ses_a", "ses_b"), result.map { it.id })
    }

    @Test
    fun `a fetch that returns nothing does not blank a populated list`() {
        val existing = listOf(session("ses_a"), session("ses_b"))
        // Empty *success* is still authoritative on a healthy connection; this documents that the
        // distinction is fetch-failure vs fetch-empty, not "any empty result".
        assertTrue(applyFetch(existing, emptyList()).isEmpty())
    }

    @Test
    fun `merging never drops a known title`() {
        val existing = listOf(session("ses_a", "Real title"))
        val result = applyFetch(existing, listOf(session("ses_a", title = "")))
        assertEquals("Real title", result.single().title)
    }

    @Test
    fun `the default new-chat directory is not a history filter`() {
        // ServerInfo.directory means "where new sessions start". Treating it as a filter on the
        // session list is what hid the user's other projects.
        val acrossLocations = listOf(
            session("ses_a", "In project one", "/home/user/project-one"),
            session("ses_b", "In project two", "/home/user/project-two"),
        )
        val defaultDirectory = "/home/user/project-one"

        val visible = applyFetch(acrossLocations, fetched = acrossLocations)
        assertEquals(
            "all locations stay visible regardless of the new-chat default",
            2,
            visible.count { it.directory != defaultDirectory || it.directory == defaultDirectory },
        )
        assertTrue(visible.any { it.directory == "/home/user/project-two" })
    }

    @Test
    fun `approval modes map to the rulesets opencode expects`() {
        assertEquals(
            listOf(PermissionRule("*", "*", "ask")),
            ApprovalMode.AskEveryTime.rules,
        )
        assertEquals(
            listOf(PermissionRule("*", "*", "allow")),
            ApprovalMode.AutoApproveAll.rules,
        )
        assertEquals(2, ApprovalMode.AutoRead.rules.size)
        assertEquals("allow", ApprovalMode.AutoRead.rules.first().effect)
        assertEquals("ask", ApprovalMode.AutoRead.rules.last().effect)
    }

    @Test
    fun `approval mode is inferred from the host's ruleset`() {
        val allowAll = SessionInfo(
            id = "ses_x",
            permissions = listOf(PermissionRule("*", "*", "allow")),
            time = io.github.nezjusz.codedroid.core.net.SessionTime(),
        )
        assertEquals(ApprovalMode.AutoApproveAll, ApprovalMode.from(allowAll))

        val askAll = SessionInfo(
            id = "ses_y",
            permissions = listOf(PermissionRule("*", "*", "ask")),
            time = io.github.nezjusz.codedroid.core.net.SessionTime(),
        )
        assertEquals(ApprovalMode.AskEveryTime, ApprovalMode.from(askAll))

        val readThenAsk = SessionInfo(
            id = "ses_z",
            permissions = listOf(
                PermissionRule("read", "*", "allow"),
                PermissionRule("*", "*", "ask"),
            ),
            time = io.github.nezjusz.codedroid.core.net.SessionTime(),
        )
        assertEquals(ApprovalMode.AutoRead, ApprovalMode.from(readThenAsk))
    }

    @Test
    fun `a session with no explicit ruleset reads as the safe mode`() {
        val inherited = SessionInfo(id = "ses_a", time = io.github.nezjusz.codedroid.core.net.SessionTime())
        assertEquals(ApprovalMode.AskEveryTime, ApprovalMode.from(inherited))
        assertEquals(ApprovalMode.AskEveryTime, ApprovalMode.from(null))
    }

    @Test
    fun `permissions decode as an array, not an object`() {
        // Regression: SessionInfo.permissions was typed JsonObject? while the server sends a
        // list, which throws on decode for any session whose approval mode has been changed.
        val json = """{"id":"ses_a","projectID":"p","cost":0,
            "tokens":{"input":0,"output":0,"reasoning":0,"cache":{}},
            "time":{"created":1,"updated":1},
            "location":{"directory":"/tmp"},
            "permissions":[{"action":"*","resource":"*","effect":"allow"}]}"""
        val decoded = io.github.nezjusz.codedroid.core.net.OpenCodeJson.decodeFromString<SessionInfo>(json)
        assertEquals(1, decoded.permissions?.size)
        assertEquals("allow", decoded.permissions?.first()?.effect)
    }

    @Test
    fun `sessions with no ruleset still decode`() {
        val json = """{"id":"ses_a","projectID":"p","cost":0,
            "tokens":{"input":0,"output":0,"reasoning":0,"cache":{}},
            "time":{"created":1,"updated":1},
            "location":{"directory":"/tmp"}}"""
        val decoded = io.github.nezjusz.codedroid.core.net.OpenCodeJson.decodeFromString<SessionInfo>(json)
        assertNull(decoded.permissions)
    }
}
