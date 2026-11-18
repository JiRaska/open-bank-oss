// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.domain

/**
 * Minimal RFC 9309 robots.txt evaluation (ADR-0387 D6). Picks the group naming [userAgentToken],
 * else `*`; the longest matching Allow/Disallow rule wins, Allow on a tie. `*` and a trailing `$`
 * are supported in patterns.
 */
class RobotsTxt private constructor(private val groups: List<Group>) {
    private class Group(val agents: List<String>, val rules: List<Rule>)

    private class Rule(val allow: Boolean, val pattern: String) {
        private val regex = Regex(
            buildString {
                append('^')
                val anchored = pattern.endsWith("$")
                val body = normalisePath(if (anchored) pattern.dropLast(1) else pattern)
                body.split('*').forEachIndexed { i, part ->
                    if (i > 0) append(".*")
                    append(Regex.escape(part))
                }
                if (anchored) append('$')
            },
        )

        fun matches(path: String) = regex.containsMatchIn(path)
    }

    fun isAllowed(userAgentToken: String, path: String): Boolean {
        val token = userAgentToken.lowercase()
        val group = groups.firstOrNull { g -> g.agents.any { it != "*" && token.startsWith(it) } }
            ?: groups.firstOrNull { "*" in it.agents }
            ?: return true
        val normalised = normalisePath(path)
        val best = group.rules.filter { it.matches(normalised) }
            .maxWithOrNull(compareBy<Rule> { it.pattern.length }.thenBy { it.allow })
        return best?.allow ?: true
    }

    companion object {
        private const val HEX = 16
        private const val ESCAPE_LEN = 3
        private val UNRESERVED: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~".toList()).toSet()

        /**
         * RFC 9309 §2.2.2 / RFC 3986 §6.2.2: percent-encoded UNRESERVED characters are decoded and
         * the remaining escapes upper-cased, on BOTH the rule and the request path. Without it
         * `/%70rivate` and `Disallow: /private` name the same resource and compare unequal.
         */
        fun normalisePath(raw: String): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val hex = if (c == '%' && i + ESCAPE_LEN <= raw.length) raw.substring(i + 1, i + ESCAPE_LEN) else null
                val code = hex?.toIntOrNull(HEX)
                if (code != null) {
                    val ch = code.toChar()
                    if (ch in UNRESERVED) {
                        out.append(ch)
                    } else {
                        out.append('%').append(hex.uppercase())
                    }
                    i += ESCAPE_LEN
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        val ALLOW_ALL = RobotsTxt(emptyList())
        val DENY_ALL = RobotsTxt(listOf(Group(listOf("*"), listOf(Rule(allow = false, pattern = "/")))))

        fun parse(text: String): RobotsTxt {
            val groups = mutableListOf<Group>()
            var agents = mutableListOf<String>()
            var rules = mutableListOf<Rule>()
            var lastWasAgent = false
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { ':' in it }.forEach { line ->
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (key) {
                    "user-agent" -> {
                        if (!lastWasAgent && agents.isNotEmpty()) {
                            groups += Group(agents, rules)
                            agents = mutableListOf()
                            rules = mutableListOf()
                        }
                        agents += value.lowercase()
                        lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        if (value.isNotEmpty()) rules += Rule(key == "allow", value)
                        lastWasAgent = false
                    }
                    else -> lastWasAgent = false
                }
            }
            if (agents.isNotEmpty()) groups += Group(agents, rules)
            return RobotsTxt(groups)
        }
    }
}
