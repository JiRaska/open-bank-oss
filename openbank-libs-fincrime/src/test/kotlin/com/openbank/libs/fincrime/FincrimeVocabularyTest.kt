// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FincrimeVocabularyTest {

    @Test
    fun `wire spellings are the published value sets, unchanged`() {
        // These literals ARE the contract (aml/sanctions/fraud openapi + pacts). Renaming one is a
        // breaking wire change, not a refactor.
        assertThat(AmlCaseSeverity.entries.map { it.name }).containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL")
        assertThat(ScreeningMatchStatus.entries.map { it.name })
            .containsExactly("CLEAR", "POTENTIAL_HIT", "HIT", "WHITELISTED", "ESCALATED")
        assertThat(
            FraudVerdict.entries.map {
                it.name
            },
        ).containsExactly("ALLOW", "CHALLENGE", "REVIEW", "DECLINE", "UNKNOWN")
    }

    @Test
    fun `severity parses known values and refuses to guess an unknown one`() {
        assertThat(AmlCaseSeverity.fromWire("CRITICAL")).isEqualTo(AmlCaseSeverity.CRITICAL)
        assertThat(AmlCaseSeverity.fromWire(" high ")).isEqualTo(AmlCaseSeverity.HIGH)
        assertThat(AmlCaseSeverity.fromWire("VERY_HIGH")).isNull()
        assertThat(AmlCaseSeverity.fromWire(null)).isNull()
    }

    @Test
    fun `an unreadable sanctions status fails closed to ESCALATED`() {
        assertThat(ScreeningMatchStatus.fromWire("potential_hit")).isEqualTo(ScreeningMatchStatus.POTENTIAL_HIT)
        assertThat(ScreeningMatchStatus.fromWire("CLEAR")).isEqualTo(ScreeningMatchStatus.CLEAR)
        assertThat(ScreeningMatchStatus.fromWire("NO_MATCH")).isEqualTo(ScreeningMatchStatus.ESCALATED)
        assertThat(ScreeningMatchStatus.fromWire(null)).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `an unreadable fraud verdict fails closed to UNKNOWN, never ALLOW`() {
        assertThat(FraudVerdict.fromWire("allow")).isEqualTo(FraudVerdict.ALLOW)
        assertThat(FraudVerdict.fromWire("DECLINE")).isEqualTo(FraudVerdict.DECLINE)
        assertThat(FraudVerdict.fromWire("APPROVE")).isEqualTo(FraudVerdict.UNKNOWN)
        assertThat(FraudVerdict.fromWire("")).isEqualTo(FraudVerdict.UNKNOWN)
        assertThat(FraudVerdict.fromWire(null)).isEqualTo(FraudVerdict.UNKNOWN)
    }

    private fun r(status: ScreeningMatchStatus, score: Double = 0.0) =
        ScreeningResult("n", ScreeningRole.DEBTOR, status, score, null)

    @Test
    fun `policy blocks a hit, an escalation and a potential hit above the threshold`() {
        assertThat(ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.HIT)))).isEqualTo(ScreeningDecision.BLOCK)
        assertThat(ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.ESCALATED)))).isEqualTo(ScreeningDecision.BLOCK)
        assertThat(
            ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.POTENTIAL_HIT, 0.86))),
        ).isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `policy reviews a potential hit at the threshold and clears clean or whitelisted names`() {
        assertThat(
            ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.POTENTIAL_HIT, 0.85))),
        ).isEqualTo(ScreeningDecision.REVIEW)
        assertThat(ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.CLEAR), r(ScreeningMatchStatus.WHITELISTED))))
            .isEqualTo(ScreeningDecision.CLEAR)
        assertThat(ScreeningPolicy.decide(emptyList())).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `block dominates review`() {
        val results = listOf(r(ScreeningMatchStatus.POTENTIAL_HIT, 0.5), r(ScreeningMatchStatus.HIT))
        assertThat(ScreeningPolicy.decide(results)).isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `an unreadable wire status ends in BLOCK end to end`() {
        assertThat(
            ScreeningPolicy.decide(listOf(r(ScreeningMatchStatus.fromWire("garbage")))),
        ).isEqualTo(ScreeningDecision.BLOCK)
    }
}
