package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.PermissionRule
import io.github.nezjusz.codedroid.core.net.SessionInfo

/**
 * How much the agent may do without asking.
 *
 * opencode stores this as a ruleset on the session (`PATCH /api/session/{id}` with
 * `permissions`), so each mode is literally a ruleset that gets written to the host.
 */
enum class ApprovalMode(
    val label: String,
    val summary: String,
    val rules: List<PermissionRule>,
) {
    /** Nothing proceeds without a decision. Safest, and what a fresh session inherits. */
    AskEveryTime(
        label = "Ask every time",
        summary = "Every action waits for your approval",
        rules = listOf(
            PermissionRule(action = "*", resource = "*", effect = "ask"),
        ),
    ),

    /** Reading is routine; anything that writes or executes still needs a decision. */
    AutoRead(
        label = "Auto for reads",
        summary = "Read files automatically, ask before writing or running commands",
        rules = listOf(
            PermissionRule(action = "read", resource = "*", effect = "allow"),
            PermissionRule(action = "*", resource = "*", effect = "ask"),
        ),
    ),

    /** Nothing prompts. Only pick this for a trusted host and a session you are watching. */
    AutoApproveAll(
        label = "Auto-approve all",
        summary = "No prompts. The agent can do anything in this session",
        rules = listOf(
            PermissionRule(action = "*", resource = "*", effect = "allow"),
        ),
    );

    companion object {
        /**
         * Infers the current mode from the rules the host reports.
         *
         * A session with no explicit ruleset inherits its agent's defaults, which treat `*.env`
         * as `ask` and everything else as `allow`. That is neither of the two "ask" modes, so it is
         * reported as [AskEveryTime]: the safe reading, and the one that matches what the user
         * actually experiences for anything sensitive.
         */
        fun from(session: SessionInfo?): ApprovalMode {
            val rules = session?.permissions.orEmpty()
            if (rules.isEmpty()) return AskEveryTime

            val catchAll = rules.firstOrNull { it.action == "*" && it.resource == "*" }
            return when (catchAll?.effect) {
                "allow" -> AutoApproveAll
                "ask" -> if (rules.any { it.action == "read" && it.effect == "allow" }) AutoRead else AskEveryTime
                else -> AskEveryTime
            }
        }
    }
}
