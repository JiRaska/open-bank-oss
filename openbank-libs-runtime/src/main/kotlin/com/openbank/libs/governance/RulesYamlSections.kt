// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

/**
 * Best-effort, dependency-free readers for the small set of `openbank-libs/governance/rules.yaml`
 * sections that control-plane agents need at runtime (`money_path_services`, `review`, and the
 * `allowlist:` blocks of individual rules).
 *
 * Why a text scan and not a YAML parser: rules.yaml is a 3.6k-line governance document whose
 * conventions (rule keys nested two levels under `rules:`, multi-line comments inside list
 * entries) are stable and grep-verifiable — the same best-effort style docs-truth-agent's
 * GovernanceRulesAdapter already uses for `enforced:` status. Pulling a real YAML dependency into
 * libs-runtime would put it on every consuming service's classpath for three sections.
 *
 * Contract: every reader returns `null`/`emptySet` when its section is absent or malformed —
 * callers decide their own fallback (a config mirror, an empty allowlist). Nothing here throws.
 */
object RulesYamlSections {

    /**
     * The `money_path_services:` top-level list — service names with the `openbank-` prefix,
     * e.g. `openbank-ledger-service`. Inline and free-standing comment lines inside the list are
     * skipped; the scan stops at the first line at (or above) the key's own indentation.
     */
    fun moneyPathServices(lines: List<String>): Set<String> = scalarList(lines, key = "money_path_services")

    /**
     * `review.default_approvals` and `review.money_path_approvals` as a pair, or `null` when the
     * `review:` section or either key is missing/malformed.
     */
    fun reviewApprovals(lines: List<String>): Pair<Int, Int>? {
        val section = section(lines, "review") ?: return null
        val defaults = section.firstNotNullOfOrNull { intValue(it, "default_approvals") }
        val moneyPath = section.firstNotNullOfOrNull { intValue(it, "money_path_approvals") }
        return if (defaults != null && moneyPath != null) defaults to moneyPath else null
    }

    /**
     * The `- <entryKey>: "<value>"` entries of a rule's `allowlist:` block — e.g.
     * `allowlistValues(lines, "event_consumer_liveness", "topic")` returns the producer-only
     * topic exemptions, `("lineage_code_audit", "edge")` the unverified-lineage edges. Returns an
     * empty set when the rule or its allowlist has no entries (indistinguishable from "no
     * allowlist" by design — the caller's contract is allowlist-membership, not allowlist-existence).
     */
    fun allowlistValues(lines: List<String>, ruleKey: String, entryKey: String): Set<String> {
        val rule = section(lines, ruleKey) ?: return emptySet()
        val allowlist = section(rule, "allowlist") ?: return emptySet()
        return allowlist.mapNotNull { entryValue(it, entryKey) }.toSet()
    }

    private fun scalarList(lines: List<String>, key: String): Set<String> = section(lines, key)
        ?.mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith(LIST_MARKER)) return@mapNotNull null
            cleanValue(trimmed.removePrefix(LIST_MARKER))
        }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

    /**
     * The lines belonging to a YAML key: everything from the line after `<key>:` up to (but not
     * including) the next line whose indentation is <= the key's. Blank and comment-only lines
     * belong to whichever side the surrounding content puts them on — they are carried through
     * and ignored by the extractors.
     */
    private fun section(lines: List<String>, key: String): List<String>? {
        val start = lines.indexOfFirst { it.trim() == "$key:" }
        if (start < 0) return null
        val keyIndent = indentOf(lines[start])
        // Blank and comment-only lines belong to whichever side the surrounding content puts
        // them on — they are carried through (and ignored by the extractors) so a comment block
        // or blank line between entries does not end the section.
        return lines.drop(start + 1).takeWhile { line ->
            line.isBlank() || line.trimStart().startsWith(COMMENT_MARKER) || indentOf(line) > keyIndent
        }
    }

    private fun entryValue(line: String, entryKey: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(LIST_MARKER)) return null
        val body = trimmed.removePrefix(LIST_MARKER).trim()
        if (!body.startsWith("$entryKey:")) return null
        return cleanValue(body.removePrefix("$entryKey:")).ifEmpty { null }
    }

    private fun intValue(line: String, key: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("$key:")) return null
        return trimmed.removePrefix("$key:").trim().toIntOrNull()
    }

    /**
     * A rules.yaml scalar value: double-quoted values end at the CLOSING quote (so a `#` inside
     * the value, or a trailing comment after it, never leaks in); unquoted values take the simple
     * ` #` comment-stripped path.
     */
    private fun cleanValue(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith(QUOTE)) {
            return value.substringBefore(" $COMMENT_MARKER").trim()
        }
        val closing = value.indexOf(QUOTE, startIndex = 1)
        return if (closing >= 1) value.substring(1, closing) else value
    }

    private fun indentOf(line: String): Int = line.takeWhile(Char::isWhitespace).length

    private const val LIST_MARKER = "-"
    private const val COMMENT_MARKER = "#"
    private const val QUOTE = "\""
}
