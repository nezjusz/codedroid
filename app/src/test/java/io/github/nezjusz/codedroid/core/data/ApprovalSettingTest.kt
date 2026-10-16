package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.PermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chip used to report "Ask every time" for every session that had not been given an explicit
 * ruleset. But a session with no rules of its own runs under its *agent's*, and the stock `build`
 * agent allows nearly everything - so the chip was stating the opposite of the truth on a host
 * where the agent acts without asking.
 *
 * The rules below are copied from a real host's `build` agent, not invented.
 */
class ApprovalSettingTest {

    /** The stock `build` agent, verbatim from `GET /api/agent`. */
    private val buildAgent = listOf(
        PermissionRule(action = "*", resource = "*", effect = "allow"),
        PermissionRule(action = "read", resource = "*.env", effect = "ask"),
        PermissionRule(action = "read", resource = "*.env.*", effect = "ask"),
        PermissionRule(action = "read", resource = "*.env.example", effect = "allow"),
        PermissionRule(action = "question", resource = "*", effect = "allow"),
        PermissionRule(action = "browser", resource = "*", effect = "deny"),
    )

    @Test
    fun `an inherited session reports the agent's rules, not the safest preset`() {
        val setting = ApprovalSetting.from(sessionRules = null, agentRules = buildAgent)
        assertNull("build's ruleset is not any preset", setting.mode)
        assertEquals(ApprovalSetting.HOST_DEFAULT_LABEL, setting.label)
    }

    @Test
    fun `an inheriting agent that allows broadly still counts as auto-approving`() {
        assertTrue(ApprovalSetting.from(null, buildAgent).autoApproves)
    }

    @Test
    fun `session rules win over the agent's`() {
        val session = ApprovalMode.AskEveryTime.rules
        val setting = ApprovalSetting.from(sessionRules = session, agentRules = buildAgent)
        assertEquals(ApprovalMode.AskEveryTime, setting.mode)
        assertFalse("explicit ask-every-time must not report as auto-approve", setting.autoApproves)
    }

    @Test
    fun `each preset is recognised as itself`() {
        for (mode in ApprovalMode.entries) {
            val setting = ApprovalSetting.from(mode.rules, null)
            assertEquals("round trip failed for $mode", mode, setting.mode)
        }
    }

    @Test
    fun `rule order does not matter`() {
        val shuffled = ApprovalMode.AutoRead.rules.reversed()
        assertEquals(ApprovalMode.AutoRead, ApprovalSetting.from(shuffled, null).mode)
    }

    @Test
    fun `unknown falls back to the safe reading`() {
        val setting = ApprovalSetting.from(null, null)
        assertEquals(ApprovalMode.AskEveryTime, setting.mode)
    }

    @Test
    fun `an empty ruleset is treated as unknown rather than as a preset`() {
        assertEquals(ApprovalSetting.Unknown, ApprovalSetting.from(emptyList(), emptyList()))
    }

    @Test
    fun `the summary names what the host still asks about`() {
        val summary = ApprovalSetting.from(null, buildAgent).summary
        assertTrue("expected .env in: $summary", summary.contains("*.env"))
        assertTrue("expected it to say it acts first: $summary", summary.contains("without asking"))
    }

    @Test
    fun `an ask-every-time host is not described as acting without asking`() {
        val summary = ApprovalSetting.from(ApprovalMode.AskEveryTime.rules, null).summary
        assertTrue("wrong summary: $summary", summary.contains("Every action waits"))
    }

    @Test
    fun `a restrictive agent does not claim to auto-approve`() {
        val restrictive = listOf(PermissionRule(action = "*", resource = "*", effect = "ask"))
        assertFalse(ApprovalSetting.from(null, restrictive).autoApproves)
    }

    @Test
    fun `the real agent ruleset is not mistaken for auto-approve-all`() {
        // `build` allows broadly but still asks for secrets, so it must not read as "allow all".
        val setting = ApprovalSetting.from(null, buildAgent)
        assertTrue(setting.mode != ApprovalMode.AutoApproveAll)
    }
}