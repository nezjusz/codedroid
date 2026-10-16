package io.github.nezjusz.codedroid.core.data

import io.github.nezjusz.codedroid.core.net.PermissionRule

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
    /** Nothing proceeds without a decision. Safest of the three presets. */
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
}

/**
 * The permission state a session is actually running under.
 *
 * [mode] is one of the presets only when the effective ruleset is exactly that preset. A host can
 * define anything it likes - the stock `build` agent allows everything except reads of `.env`
 * files - and calling that "Ask every time" was simply untrue, which is what the chip used to do
 * for every session that had not been given an explicit ruleset.
 *
 * `mode` is null for such a host-defined ruleset: there is no way to round-trip it into a preset,
 * so it is reported rather than offered as a choice.
 */
data class ApprovalSetting(
    val mode: ApprovalMode?,
    val rules: List<PermissionRule>,
) {

    val label: String get() = mode?.label ?: HOST_DEFAULT_LABEL

    val summary: String
        get() = mode?.summary ?: hostDefaultSummary()

    /**
     * True when the catch-all allows, so the agent acts without asking.
     *
     * Drives the standing warning. A host ruleset that allows broadly but still asks for secrets
     * needs this just as much as a full auto-approve, and the difference is a detail the user
     * does not need to read a banner about.
     */
    val autoApproves: Boolean
        get() = rules.any { it.action == "*" && it.resource == "*" && it.effect == "allow" }

    /** What the host restricts, in plain words, for the chip's tooltip and the sheet. */
    private fun hostDefaultSummary(): String {
        val restricted = rules.filter { it.action != "*" || it.resource != "*" }
        val asked = restricted.filter { it.effect == "ask" }.map { it.resource }.distinct()
        val denied = restricted.filter { it.effect == "deny" }.map { it.resource }.distinct()
        return buildString {
            append(if (autoApproves) "Acts without asking" else "Asks per rule")
            if (asked.isNotEmpty()) append(", except ").append(asked.take(2).joinToString(", "))
            if (denied.isNotEmpty()) append(", never ").append(denied.take(2).joinToString(", "))
        }
    }

    companion object {
        const val HOST_DEFAULT_LABEL = "Host default"

        /**
         * What the chip shows before the host has told us anything.
         *
         * Unknown is reported as the safest preset rather than as a confident claim.
         */
        val Unknown = ApprovalSetting(ApprovalMode.AskEveryTime, emptyList())

        /**
         * Resolves the effective ruleset: the session's own if it has one, otherwise the agent's.
         *
         * A session with no explicit `permissions` is not restricted - it inherits its agent, and
         * the default agent allows nearly everything. Reading that as "ask every time" hid the
         * one fact a user most needs before letting an agent run unattended.
         */
        fun from(sessionRules: List<PermissionRule>?, agentRules: List<PermissionRule>?): ApprovalSetting {
            val rules = sessionRules?.takeIf { it.isNotEmpty() } ?: agentRules.orEmpty()
            if (rules.isEmpty()) return Unknown
            return ApprovalSetting(mode = classify(rules), rules = rules)
        }

        /** The preset whose rules are exactly [rules], or null when nothing matches. */
        private fun classify(rules: List<PermissionRule>): ApprovalMode? {
            // Compared as a set: the host is free to order its rules, and the presets carry a
            // specific order that carries no meaning.
            val normalised = rules
                .map { "${it.action}|${it.resource}|${it.effect}" }
                .toSortedSet()
            return ApprovalMode.entries.firstOrNull { mode ->
                mode.rules
                    .map { "${it.action}|${it.resource}|${it.effect}" }
                    .toSortedSet() == normalised
            }
        }
    }
}