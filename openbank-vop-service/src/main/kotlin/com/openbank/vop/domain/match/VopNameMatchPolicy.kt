// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.domain.match

import com.openbank.libs.identity.MatchKey
import com.openbank.vop.domain.model.VopOutcome
import kotlin.math.abs
import kotlin.math.min

/**
 * Decides whether a payer-supplied payee name is the same name as the one held on the account
 * (ADR-0171 §2, IPR Art. 5c). Pure domain logic: deterministic, symmetric, clock-free,
 * framework-free.
 *
 * The comparison is a three-band decision, not a score. VoP asks one question — "is this the
 * same name?" — with three useful answers: yes ([VopOutcome.MATCH]), nearly, and a payer could
 * plausibly fix it ([VopOutcome.CLOSE_MATCH]), or no ([VopOutcome.NO_MATCH]). A
 * [VopOutcome.NO_DATA] is *not* this policy's business: it means we never got a name to compare,
 * which is a lookup concern, so this policy is only ever called with two present names.
 *
 * Normalization is delegated to [MatchKey.normalize] (NFD, strip diacritics, lowercase, collapse
 * whitespace) rather than re-implemented. Two copies of that normaliser already exist in the
 * fleet — `MatchKey` itself and `ProbabilisticMatcher`'s deliberately inlined duplicate — and a
 * third would be the drift (ADR-0171 §2). This service is a separate module and can depend on
 * `:openbank-libs-domain`, so it does.
 *
 * The `CLOSE_MATCH` band deliberately mirrors `ProbabilisticMatcher.compareName`'s
 * EXACT/NEAR/DISAGREE primitive (normalise, then bounded Levenshtein) so the two never disagree
 * about what "nearly the same string" means. What it adds is the name-shape tolerance a SEPA
 * name field needs and a structured identity tuple does not: token reordering, initials, and
 * legal-form suffixes.
 *
 * @param maxEditDistance the Levenshtein budget for a typo-tolerant CLOSE_MATCH. 1 by default —
 *   a single character. A judgement call with no production data behind it (ADR-0171), which is
 *   why it is a constructor parameter and not a constant.
 */
class VopNameMatchPolicy(private val maxEditDistance: Int = DEFAULT_MAX_EDIT_DISTANCE) {

    init {
        require(maxEditDistance >= 0) { "maxEditDistance must not be negative, was $maxEditDistance" }
    }

    /**
     * Compare [suppliedName] (what the payer typed) against [accountHolderName] (what we hold).
     * Both must be present and non-blank — an absent name is a NO_DATA case the caller resolves
     * before reaching this policy.
     *
     * The comparison is symmetric: `match(a, b) == match(b, a)`.
     */
    fun match(suppliedName: String, accountHolderName: String): VopOutcome {
        val supplied = MatchKey.normalize(suppliedName)
        val actual = MatchKey.normalize(accountHolderName)
        require(supplied.isNotBlank() && actual.isNotBlank()) {
            "both names must be present; a missing name is a NO_DATA case, not a NO_MATCH"
        }

        if (supplied == actual) return VopOutcome.MATCH

        val suppliedCore = stripLegalForms(supplied)
        val actualCore = stripLegalForms(actual)
        // A legal-form suffix is presentation, not identity: "Acme s.r.o." and "Acme" are the
        // same payee. Only the suffix differed, so this is a full MATCH, not a near-miss.
        if (suppliedCore.isNotBlank() && suppliedCore == actualCore) return VopOutcome.MATCH

        return if (isCloseMatch(suppliedCore, actualCore)) VopOutcome.CLOSE_MATCH else VopOutcome.NO_MATCH
    }

    private fun isCloseMatch(supplied: String, actual: String): Boolean {
        if (supplied.isBlank() || actual.isBlank()) return false

        val suppliedTokens = supplied.split(TOKEN_SEPARATOR).filter { it.isNotBlank() }
        val actualTokens = actual.split(TOKEN_SEPARATOR).filter { it.isNotBlank() }

        // Reordering: "Jiří Raška" vs "Raška Jiří". Same tokens, different order — the payer knows
        // the name, their PSP's field order differs from ours.
        if (suppliedTokens.sorted() == actualTokens.sorted()) return true

        // Initials: "J. Raška" / "J Raska" vs "Jiří Raška". One side abbreviates given names.
        if (matchesWithInitials(suppliedTokens, actualTokens)) return true

        // A dropped or added token, the rest matching exactly: "Jiří Raška" vs "Jiří Jan Raška".
        // Common where one side carries a middle name the other omits.
        if (isSubsetByOneToken(suppliedTokens, actualTokens)) return true

        // A single-character typo anywhere in the whole string.
        return levenshteinWithin(supplied, actual, maxEditDistance)
    }

    /**
     * True when the two token lists are the same name with given names abbreviated to initials on
     * one side. Requires equal token counts and at least one real (non-initial) token in common,
     * so "J. K." never matches "Jan Kovář" on initials alone — that is far too weak to call a
     * near-miss of a payee name.
     */
    private fun matchesWithInitials(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var fullTokenAgreements = 0
        val allPositionsAgree = a.indices.all { i ->
            val left = a[i]
            val right = b[i]
            when {
                left == right -> {
                    if (left.length > 1) fullTokenAgreements++
                    true
                }
                isInitialOf(left, right) || isInitialOf(right, left) -> true
                else -> false
            }
        }
        return allPositionsAgree && fullTokenAgreements > 0
    }

    /** True when [initial] is a one-letter abbreviation (with or without a dot) of [full]. */
    private fun isInitialOf(initial: String, full: String): Boolean {
        val bare = initial.removeSuffix(".")
        return bare.length == 1 && full.length > 1 && full.startsWith(bare)
    }

    /**
     * True when one token list is the other minus exactly one token, order preserved — the
     * middle-name case ("Jiří Raška" vs "Jiří Jan Raška").
     *
     * Requires at least two tokens to survive the drop. Otherwise a one-token name would near-miss
     * anything containing it: "Acme Praha" vs "Praha" would pass on a single shared token, which is
     * two different payees, not a typo. With one token left there is nothing corroborating it.
     */
    private fun isSubsetByOneToken(a: List<String>, b: List<String>): Boolean {
        val (longer, shorter) = if (a.size > b.size) a to b else b to a
        if (longer.size - shorter.size != 1 || shorter.size < MIN_CORROBORATING_TOKENS) return false
        return longer.indices.any { skipped ->
            longer.filterIndexed { i, _ -> i != skipped } == shorter
        }
    }

    /**
     * Strip trailing legal-form designators. Only a *trailing* suffix is removed: "s.r.o." at the
     * end of "Acme s.r.o." is a legal form, but a company genuinely named "SRO Praha" keeps its
     * leading token. Returns the input unchanged when stripping would empty it.
     */
    private fun stripLegalForms(normalized: String): String {
        var result = normalized
        var changed = true
        while (changed) {
            changed = false
            for (form in LEGAL_FORMS) {
                val candidate = result.removeSuffix(form).trim().removeSuffix(",").trim()
                if (candidate != result && candidate.isNotBlank()) {
                    result = candidate
                    changed = true
                }
            }
        }
        return result
    }

    companion object {
        /** One character — a typo, not a different name. */
        const val DEFAULT_MAX_EDIT_DISTANCE = 1

        /** Tokens that must remain after a dropped-token near-miss for it to mean anything. */
        private const val MIN_CORROBORATING_TOKENS = 2

        private val TOKEN_SEPARATOR = Regex("[\\s,]+")

        /**
         * Trailing legal-form designators, normalised (lowercase, no diacritics) to match
         * post-[MatchKey.normalize] input. CZ/SK forms first, then the common EU ones a SEPA
         * counterparty name is likely to carry.
         */
        private val LEGAL_FORMS = listOf(
            "s.r.o.", "s r o", "sro", "spol. s r.o.", "spol s r o",
            "a.s.", "a s", "as", "k.s.", "v.o.s.", "z.s.", "o.p.s.", "s.p.",
            "gmbh", "ag", "kg", "ohg", "ug", "mbh",
            "ltd", "ltd.", "limited", "plc", "llc", "inc", "inc.", "corp", "corp.",
            "sa", "s.a.", "sas", "sarl", "s.a.r.l.", "bv", "b.v.", "nv", "n.v.",
            "oy", "ab", "as.", "aps", "spa", "s.p.a.", "srl", "s.r.l.", "sp. z o.o.", "sp z o o",
        )

        /**
         * True iff the Levenshtein edit distance between [a] and [b] is at most [max]. Early-exits
         * on a length gap greater than [max]; bounded two-row DP otherwise. Mirrors
         * `ProbabilisticMatcher.levenshteinWithin` so the two agree on "one edit apart".
         */
        private fun levenshteinWithin(a: String, b: String, max: Int): Boolean {
            if (a == b) return true
            if (abs(a.length - b.length) > max) return false
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
                    rowMin = min(rowMin, current[j])
                }
                if (rowMin > max) return false
                val tmp = previous
                previous = current
                current = tmp
            }
            return previous[b.length] <= max
        }
    }
}
