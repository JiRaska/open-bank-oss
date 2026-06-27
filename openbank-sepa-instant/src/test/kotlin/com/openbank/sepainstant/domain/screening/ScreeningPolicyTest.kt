// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.domain.screening

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScreeningPolicyTest {

    private fun result(
        status: ScreeningMatchStatus,
        score: Double = 0.0,
        role: ScreeningRole = ScreeningRole.CREDITOR
    ) = ScreeningResult("Subject", role, status, score, matchedEntity = null)

    @Test
    fun `empty results are CLEAR`() {
        assertThat(ScreeningPolicy.decide(emptyList())).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `all clear or whitelisted is CLEAR`() {
        val results = listOf(
            result(ScreeningMatchStatus.CLEAR, role = ScreeningRole.DEBTOR),
            result(ScreeningMatchStatus.WHITELISTED, score = 1.0)
        )
        assertThat(ScreeningPolicy.decide(results)).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `a confirmed HIT blocks`() {
        val results = listOf(result(ScreeningMatchStatus.CLEAR), result(ScreeningMatchStatus.HIT, 0.99))
        assertThat(ScreeningPolicy.decide(results)).isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `an ESCALATED status blocks`() {
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.ESCALATED))))
            .isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `a POTENTIAL_HIT above the threshold blocks`() {
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.POTENTIAL_HIT, 0.86))))
            .isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `a POTENTIAL_HIT exactly at the threshold is REVIEW not BLOCK`() {
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.POTENTIAL_HIT, 0.85))))
            .isEqualTo(ScreeningDecision.REVIEW)
    }

    @Test
    fun `a POTENTIAL_HIT below the threshold is REVIEW`() {
        assertThat(ScreeningPolicy.decide(listOf(result(ScreeningMatchStatus.POTENTIAL_HIT, 0.40))))
            .isEqualTo(ScreeningDecision.REVIEW)
    }

    @Test
    fun `BLOCK dominates REVIEW when both are present`() {
        val results = listOf(
            result(ScreeningMatchStatus.POTENTIAL_HIT, 0.50),  // would be REVIEW
            result(ScreeningMatchStatus.HIT, 0.95)              // BLOCK
        )
        assertThat(ScreeningPolicy.decide(results)).isEqualTo(ScreeningDecision.BLOCK)
    }
}
