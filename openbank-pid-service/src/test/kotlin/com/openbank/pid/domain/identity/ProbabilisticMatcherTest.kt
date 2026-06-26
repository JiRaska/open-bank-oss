// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.domain.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for the tier-2′ probabilistic record-linkage scorer (ADR-0094 §4).
 *
 * The scorer is pure and inert (nothing wires it into the resolver yet — that is the follow-up
 * PR alongside the candidate blocking-query and the Splink adapter), so these tests pin its
 * behaviour as a standalone building block: bands, explainability, near-match recall, the
 * diacritic/insensitivity and swapped-name patterns, and symmetry.
 */
class ProbabilisticMatcherTest {

    private val matcher = ProbabilisticMatcher()

    private fun attrs(given: String, family: String, birthdate: String, birthplace: String? = null) =
        IdentityAttributes(given, family, LocalDate.parse(birthdate), birthplace)

    @Test
    fun `identical people score high confidence with all-exact contributions`() {
        val a = attrs("Jan", "Novák", "1985-03-12", "Brno")
        val score = matcher.score(a, a)

        assertThat(score.band).isEqualTo(MatchBand.HIGH_CONFIDENCE)
        assertThat(score.weight).isGreaterThan(ProbabilisticMatcher.DEFAULT_HIGH_CONFIDENCE)
        assertThat(score.contributions).allMatch { it.comparison == Comparison.EXACT }
        assertThat(score.contributions.map { it.field })
            .containsExactlyInAnyOrder("familyName", "givenName", "birthdate", "birthplace")
    }

    @Test
    fun `diacritics and casing are normalized away — Dvorak equals Dvořák`() {
        val a = attrs("Petr", "Dvořák", "1990-07-01")
        val b = attrs("PETR", "DVORAK ", "1990-07-01")
        val score = matcher.score(a, b)

        val family = score.contributions.single { it.field == "familyName" }
        assertThat(family.comparison).isEqualTo(Comparison.EXACT)
        assertThat(score.band).isEqualTo(MatchBand.HIGH_CONFIDENCE)
    }

    @Test
    fun `a one-character surname typo is a near match, not a disagreement`() {
        val a = attrs("Eva", "Svobodová", "1978-11-23")
        val b = attrs("Eva", "Svobodova", "1978-11-23") // missing the accent already normalizes away
        val typo = attrs("Eva", "Svobdová", "1978-11-23") // dropped a letter → Levenshtein 1

        assertThat(matcher.score(a, b).contributions.single { it.field == "familyName" }.comparison)
            .isEqualTo(Comparison.EXACT)
        assertThat(matcher.score(a, typo).contributions.single { it.field == "familyName" }.comparison)
            .isEqualTo(Comparison.NEAR)
    }

    @Test
    fun `clearly different people fall below the gray zone`() {
        val a = attrs("Jan", "Novák", "1985-03-12")
        val b = attrs("Tomáš", "Procházka", "1962-09-30")
        val score = matcher.score(a, b)

        assertThat(score.band).isEqualTo(MatchBand.NO_MATCH)
        assertThat(score.weight).isLessThan(ProbabilisticMatcher.DEFAULT_GRAY_ZONE_LOWER)
    }

    @Test
    fun `swapped given and family names still match`() {
        val a = attrs("Jan", "Novák", "1985-03-12")
        val swapped = attrs("Novák", "Jan", "1985-03-12")
        val score = matcher.score(a, swapped)

        assertThat(score.band).isEqualTo(MatchBand.HIGH_CONFIDENCE)
        assertThat(score.contributions.single { it.field == "familyName" }.comparison)
            .isEqualTo(Comparison.EXACT)
        assertThat(score.contributions.single { it.field == "givenName" }.comparison)
            .isEqualTo(Comparison.EXACT)
    }

    @Test
    fun `a missing birthplace contributes neutral weight rather than penalizing`() {
        val a = attrs("Jan", "Novák", "1985-03-12", birthplace = "Brno")
        val b = attrs("Jan", "Novák", "1985-03-12", birthplace = null)
        val score = matcher.score(a, b)

        val birthplace = score.contributions.single { it.field == "birthplace" }
        assertThat(birthplace.comparison).isEqualTo(Comparison.NEUTRAL)
        assertThat(birthplace.weight).isEqualTo(0.0)
        assertThat(score.band).isEqualTo(MatchBand.HIGH_CONFIDENCE)
    }

    @Test
    fun `same year but a different day is a near birthdate match`() {
        val a = attrs("Jan", "Novák", "1985-03-12")
        val b = attrs("Jan", "Novák", "1985-03-21")
        assertThat(matcher.score(a, b).contributions.single { it.field == "birthdate" }.comparison)
            .isEqualTo(Comparison.NEAR)
    }

    @Test
    fun `an ambiguous near-miss lands in the gray zone and raises a case`() {
        // Exact given name + a one-edit surname typo + a different-year birthdate: enough to be
        // worth a human look, not enough to be high-confidence.
        val a = attrs("Jan", "Novák", "1985-03-12")
        val b = attrs("Jan", "Novan", "1991-08-05")
        val score = matcher.score(a, b)

        assertThat(score.band).isEqualTo(MatchBand.GRAY_ZONE)
        assertThat(score.weight)
            .isGreaterThanOrEqualTo(ProbabilisticMatcher.DEFAULT_GRAY_ZONE_LOWER)
            .isLessThan(ProbabilisticMatcher.DEFAULT_HIGH_CONFIDENCE)
    }

    @Test
    fun `scoring is symmetric`() {
        val a = attrs("Jan", "Novák", "1985-03-12", "Brno")
        val b = attrs("Jan", "Novak", "1985-03-12", "Praha")
        assertThat(matcher.score(a, b).weight).isEqualTo(matcher.score(b, a).weight)
    }

    @Test
    fun `an empty field on one side is neutral, not a disagreement`() {
        val a = attrs("", "Novák", "1985-03-12")
        val b = attrs("Jan", "Novák", "1985-03-12")
        assertThat(matcher.score(a, b).contributions.single { it.field == "givenName" }.comparison)
            .isEqualTo(Comparison.NEUTRAL)
    }

    @Test
    fun `same-length but distant surnames disagree`() {
        val a = attrs("Jan", "Novák", "1985-03-12")
        val b = attrs("Jan", "Bílek", "1985-03-12") // 5 vs 5 chars, edit distance well over one
        assertThat(matcher.score(a, b).contributions.single { it.field == "familyName" }.comparison)
            .isEqualTo(Comparison.DISAGREE)
    }

    @Test
    fun `thresholds are configurable`() {
        val strict = ProbabilisticMatcher(grayZoneLowerWeight = 30.0, highConfidenceWeight = 40.0)
        val a = attrs("Jan", "Novák", "1985-03-12")
        // A solid match that is HIGH under defaults is only NO_MATCH under deliberately strict bands.
        assertThat(strict.score(a, a).band).isIn(MatchBand.NO_MATCH, MatchBand.GRAY_ZONE)
    }

    @Test
    fun `gray zone lower bound must be below the high-confidence bound`() {
        assertThatThrownBy { ProbabilisticMatcher(grayZoneLowerWeight = 10.0, highConfidenceWeight = 5.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
