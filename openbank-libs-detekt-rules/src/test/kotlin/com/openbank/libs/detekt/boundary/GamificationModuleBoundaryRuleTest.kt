// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.boundary

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Falsified per this repo's own rule that a gate which has only ever passed is unfalsified
 * (`.claude/CLAUDE.md`): a lending-package import of gamification state is proven to FLAG, and an
 * engagement-package import of its own gamification state (the normal, legitimate case) is proven
 * to pass — not assumed from the rule reading correct.
 */
class GamificationModuleBoundaryRuleTest {

    private val rule = GamificationModuleBoundaryRule()

    @Test
    fun `flags a lending package importing gamification domain state`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.lending.application

            import com.openbank.engagement.domain.model.gamification.Points

            class LoanEligibilityCalculator {
                fun boost(points: Points) {}
            }
            """.trimIndent(),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("must not depend on gamification domain state")
    }

    @Test
    fun `flags a decisioning package importing an earn source type`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.decisioning.domain

            import com.openbank.engagement.domain.model.gamification.EarnSource

            class CreditRule {
                fun evaluate(source: EarnSource) {}
            }
            """.trimIndent(),
        )
        assertThat(findings).hasSize(1)
    }

    @Test
    fun `flags a wildcard import of the gamification package`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.lending.domain

            import com.openbank.engagement.domain.model.gamification.*

            class LoanScorer
            """.trimIndent(),
        )
        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not flag the engagement module importing its own gamification state`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.engagement.application.usecase

            import com.openbank.engagement.domain.model.gamification.Points

            class AwardGamificationPointsUseCase {
                fun award(points: Points) {}
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not flag a lending package importing something unrelated`() {
        val findings = rule.compileAndLint(
            """
            package com.openbank.lending.application

            import com.openbank.libs.domain.identifiers.Ids

            class LoanOriginationService {
                fun newId() = Ids.newId()
            }
            """.trimIndent(),
        )
        assertThat(findings).isEmpty()
    }
}
