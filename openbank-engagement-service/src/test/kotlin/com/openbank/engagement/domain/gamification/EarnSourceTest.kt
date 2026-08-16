// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.gamification

import com.openbank.engagement.domain.model.gamification.EarnSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EarnSourceTest {

    /** The catalogue is closed by construction: this `when` has no `else` branch and the file does
     *  not compile once a variant is added without a matching arm — the test IS the exhaustiveness
     *  check, there is nothing further to assert at runtime. */
    private fun labelFor(source: EarnSource): String = when (source) {
        EarnSource.EducationalContentCompletion -> "educational content"
        EarnSource.SavingsGoalDeposit -> "savings goal deposit"
        EarnSource.LoginStreak -> "login streak"
        EarnSource.OnTimeRepayment -> "on-time repayment"
    }

    @Test
    fun `every catalogue entry maps to a label and none rewards credit uptake`() {
        EarnSource.ALL.forEach { source ->
            assertThat(labelFor(source)).isNotBlank
            // ADR-0220's Alternatives-considered rejects rewarding credit uptake absolutely; a
            // closed sealed catalogue makes "no such variant exists" the actual proof, not a
            // string check — this asserts the ids stay human-reviewable, not credit jargon.
            assertThat(source.id).doesNotContainIgnoringCase("credit").doesNotContainIgnoringCase("loan")
        }
    }

    @Test
    fun `ids are unique`() {
        assertThat(EarnSource.ALL.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `byId resolves a known id and returns null for an unknown one`() {
        assertThat(EarnSource.byId("EDUCATIONAL_CONTENT_COMPLETION")).isEqualTo(EarnSource.EducationalContentCompletion)
        assertThat(EarnSource.byId("NOT_A_REAL_SOURCE")).isNull()
    }
}
