// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.identity

import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.min

/**
 * Probabilistic record-linkage scorer — the tier-2′ additional candidate source of ADR-0094 §4.
 *
 * ADR-0072 resolves applicants without a Czech RČ with a conservative *exact-normalized-tuple*
 * match: high precision, but it misses real duplicates (a transliterated name, `Müller`
 * vs `Mueller`, a swapped given/family order, a single-digit birthdate typo). This scorer raises
 * **recall** on those near-misses by computing an explainable Fellegi–Sunter match weight, so they
 * can be surfaced — and it preserves the ADR-0072 invariant verbatim: **a score never auto-merges
 * and never auto-creates; it only decides whether a four-eyes case should be raised.** The
 * adjudication stays human.
 *
 * The model is the classical Fellegi–Sunter record-linkage formulation. For each comparison field
 * we hold two priors: `m` = P(the field agrees | the records are the same person) and
 * `u` = P(the field agrees | the records are different people). A field that agrees contributes
 * the agreement weight `log2(m / u)` (positive — agreement is evidence *for* a match); a field
 * that disagrees contributes the disagreement weight `log2((1 − m) / (1 − u))` (negative). The
 * total match weight is the sum across fields; higher means more likely the same person. A "near"
 * comparison (one-edit typo, or a same-year birthdate slip) contributes a fraction of the
 * agreement weight, and a field that is absent on either side contributes nothing (neutral).
 *
 * Every comparison is recorded as a [FieldContribution] so a reviewer (and an auditor) can see
 * *why* a weight landed where it did — the property ADR-0072 required of the no-RČ tier and the
 * reason a probabilistic score may inform, but never replace, the manual decision.
 *
 * This is pure domain logic: deterministic, clock-free, framework-free, and symmetric
 * (`score(a, b) == score(b, a)`). The production deployment may swap this in-process scorer for the
 * Splink service adapter (ADR-0094 §4) behind the same contract; the thresholds are constructor
 * parameters so they are config-tunable without touching the algorithm.
 */
class ProbabilisticMatcher(
    private val grayZoneLowerWeight: Double = DEFAULT_GRAY_ZONE_LOWER,
    private val highConfidenceWeight: Double = DEFAULT_HIGH_CONFIDENCE,
) {

    init {
        require(grayZoneLowerWeight < highConfidenceWeight) {
            "grayZoneLowerWeight ($grayZoneLowerWeight) must be below highConfidenceWeight ($highConfidenceWeight)"
        }
    }

    /**
     * Score [candidate] against [applicant] and band the result. The arguments are interchangeable
     * — scoring is symmetric.
     */
    fun score(applicant: IdentityAttributes, candidate: IdentityAttributes): MatchScore {
        // Cross-cultural given/family inversion: when the names are swapped between the records,
        // both name fields are treated as agreeing (attributed explicitly so the swap is visible).
        val swapped = isNameOrderSwapped(applicant, candidate)
        val (familyComparison, givenComparison) = if (swapped) {
            Comparison.EXACT to Comparison.EXACT
        } else {
            compareName(applicant.familyName, candidate.familyName) to
                compareName(applicant.givenName, candidate.givenName)
        }
        val birthdateComparison = compareBirthdate(applicant.birthdate, candidate.birthdate)
        val birthplaceComparison = compareOptional(applicant.birthplace, candidate.birthplace)

        val contributions = listOf(
            weightFor(FIELD_FAMILY_NAME, familyComparison, M_FAMILY_NAME, U_FAMILY_NAME),
            weightFor(FIELD_GIVEN_NAME, givenComparison, M_GIVEN_NAME, U_GIVEN_NAME),
            weightFor(FIELD_BIRTHDATE, birthdateComparison, M_BIRTHDATE, U_BIRTHDATE),
            weightFor(FIELD_BIRTHPLACE, birthplaceComparison, M_BIRTHPLACE, U_BIRTHPLACE),
        )

        val weight = contributions.sumOf { it.weight }
        val band = when {
            weight >= highConfidenceWeight -> MatchBand.HIGH_CONFIDENCE
            weight >= grayZoneLowerWeight -> MatchBand.GRAY_ZONE
            else -> MatchBand.NO_MATCH
        }
        return MatchScore(weight = weight, band = band, contributions = contributions)
    }

    private fun weightFor(field: String, comparison: Comparison, m: Double, u: Double): FieldContribution {
        val agree = ln(m / u) / LN_2
        val nearWeight = agree * NEAR_AGREEMENT_FRACTION
        val disagree = ln((1.0 - m) / (1.0 - u)) / LN_2
        val weight = when (comparison) {
            Comparison.EXACT -> agree
            Comparison.NEAR -> nearWeight
            Comparison.DISAGREE -> disagree
            Comparison.NEUTRAL -> 0.0
        }
        return FieldContribution(field = field, comparison = comparison, weight = weight)
    }

    private fun compareName(a: String, b: String): Comparison {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return Comparison.NEUTRAL
        if (na == nb) return Comparison.EXACT
        return if (levenshteinWithin(na, nb, MAX_NAME_EDIT_DISTANCE)) Comparison.NEAR else Comparison.DISAGREE
    }

    // A same-year birthdate with a transposed/typo'd month or day is a common data-entry slip — a
    // "near" signal, not full agreement and not a clean disagreement.
    private fun compareBirthdate(a: LocalDate, b: LocalDate): Comparison = when {
        a == b -> Comparison.EXACT
        a.year == b.year -> Comparison.NEAR
        else -> Comparison.DISAGREE
    }

    private fun compareOptional(a: String?, b: String?): Comparison {
        if (a == null || b == null) return Comparison.NEUTRAL
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return Comparison.NEUTRAL
        return if (na == nb) Comparison.EXACT else Comparison.DISAGREE
    }

    private fun isNameOrderSwapped(a: IdentityAttributes, b: IdentityAttributes): Boolean {
        val ag = normalize(a.givenName)
        val af = normalize(a.familyName)
        val bg = normalize(b.givenName)
        val bf = normalize(b.familyName)
        if (listOf(ag, af, bg, bf).any { it.isEmpty() }) return false
        // A genuine swap only — not a same-name case where given == family on one side.
        return ag == bf && af == bg && ag != af
    }

    companion object {
        /** Default lower edge of the gray zone (bits of match weight) — at/above this, raise a case. */
        const val DEFAULT_GRAY_ZONE_LOWER: Double = 6.0

        /** Default high-confidence weight — at/above this, flag the case for priority triage. */
        const val DEFAULT_HIGH_CONFIDENCE: Double = 14.0

        const val FIELD_FAMILY_NAME = "familyName"
        const val FIELD_GIVEN_NAME = "givenName"
        const val FIELD_BIRTHDATE = "birthdate"
        const val FIELD_BIRTHPLACE = "birthplace"

        // Fellegi–Sunter priors. m = P(agree | match), u = P(agree | non-match). Surnames are more
        // discriminating than given names; an exact birthdate is the strongest single signal; a
        // shared birthplace is weakly discriminating (towns are common). These are starting points,
        // tunable from observed CZ onboarding data — they are not load-bearing constants.
        private const val M_FAMILY_NAME = 0.92
        private const val U_FAMILY_NAME = 0.0008
        private const val M_GIVEN_NAME = 0.90
        private const val U_GIVEN_NAME = 0.012
        private const val M_BIRTHDATE = 0.96
        private const val U_BIRTHDATE = 0.00007
        private const val M_BIRTHPLACE = 0.85
        private const val U_BIRTHPLACE = 0.02

        private const val NEAR_AGREEMENT_FRACTION = 0.5
        private const val MAX_NAME_EDIT_DISTANCE = 1
        private val LN_2 = ln(2.0)

        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Identical normalization to [com.openbank.libs.identity.MatchKey.normalize] — inlined to
         * keep the domain layer free of any cross-module dependency — so a probabilistic comparison
         * and the exact tier-2 match key agree on what "the same string" means.
         */
        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim()

        /**
         * True iff the Levenshtein edit distance between [a] and [b] is at most [max]. Early-exits
         * on a length gap greater than [max]; bounded two-row DP otherwise.
         */
        private fun levenshteinWithin(a: String, b: String, max: Int): Boolean {
            if (a == b) return true
            if (kotlin.math.abs(a.length - b.length) > max) return false
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

/** The minimal identity attribute bundle a probabilistic comparison needs. Framework-free. */
data class IdentityAttributes(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String? = null,
)

/** How two values of a single field compared. */
enum class Comparison {
    /** Normalized-equal. */
    EXACT,

    /** Within one edit (name typo) or a same-year birthdate slip. */
    NEAR,

    /** Present on both sides and not equal/near. */
    DISAGREE,

    /** Absent on at least one side — contributes no weight. */
    NEUTRAL,
}

/** The match-weight band a total score falls into (ADR-0094 §4 routing). */
enum class MatchBand {
    /** Below the gray zone — not a duplicate; onboarding may proceed. */
    NO_MATCH,

    /** Inside the gray zone — raise a four-eyes case for human adjudication. */
    GRAY_ZONE,

    /** Above the high-confidence edge — still a four-eyes case, flagged for priority triage. */
    HIGH_CONFIDENCE,
}

/** One field's contribution to the total match weight, for explainability. */
data class FieldContribution(val field: String, val comparison: Comparison, val weight: Double)

/** A probabilistic match result: the total weight, its band, and the per-field explanation. */
data class MatchScore(val weight: Double, val band: MatchBand, val contributions: List<FieldContribution>)
