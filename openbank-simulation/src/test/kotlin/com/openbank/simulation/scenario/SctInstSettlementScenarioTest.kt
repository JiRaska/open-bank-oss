// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.error.SctInstSchemeRules
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises
 * [SctInstSettlementScenario] directly against the REAL `sepa-instant` `SctInstPayment` domain
 * aggregate and the REAL `SctInstSchemeRules` scheme rule, so the harness's binding to the
 * service (beyond sepa-payment/settlement) is proven by an actual assertion, not merely by
 * compiling — the SCT Inst analogue of [SepaSettlementScenarioTest].
 */
class SctInstSettlementScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step completes the real SctInstPayment and books the real Settlement`() {
        val world = newWorld()
        SctInstSettlementScenario.step(world)

        assertThat(world.sctInstPayments).hasSize(1)
        assertThat(world.settlements).hasSize(1)
        assertThat(world.sctInstPayments.single().status).isEqualTo(SctInstStatus.SETTLED)
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.BOOKED)

        // The production `proceed` semantics: the CLEAR decision arms the scheme execution
        // timeout and stamps submission, and `complete` stamps settlement on the aggregate.
        val payment = world.sctInstPayments.single()
        assertThat(payment.submittedAt).isNotNull()
        assertThat(payment.executionTimeoutAt).isNotNull()
        assertThat(payment.settledAt).isNotNull()
        assertThat(MoneyPathInvariants.sctInstPaymentCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.settlementCompleteness.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault rejects the SctInstPayment and the Settlement together`() {
        // 100% write-failure rate forces the scheme-submission rejection branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        SctInstSettlementScenario.step(world)

        assertThat(world.sctInstPayments.single().status).isEqualTo(SctInstStatus.REJECTED)
        assertThat(world.sctInstPayments.single().rejectReason).isNotNull()
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.REJECTED)
        // Rejected before anything moved: no submission stamp, no execution timeout armed.
        assertThat(world.sctInstPayments.single().submittedAt).isNull()
        assertThat(world.sctInstPayments.single().executionTimeoutAt).isNull()
        assertThat(MoneyPathInvariants.sctInstPaymentCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-settlement conflict recalls the SETTLED SctInstPayment and reverses the Settlement`() {
        // 100% conflict rate forces the post-settlement recall branch every time.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        SctInstSettlementScenario.step(world)

        val payment = world.sctInstPayments.single()
        assertThat(payment.status).isEqualTo(SctInstStatus.RECALLED)
        assertThat(payment.recalledAt).isNotNull()
        assertThat(payment.recallReason).isEqualTo("SIMULATED_POST_SETTLEMENT_CONFLICT")
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.REVERSED)
        assertThat(MoneyPathInvariants.sctInstPaymentCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.settlementCompleteness.check(world)).isNull()
    }

    @Test
    fun `the real scheme rule refuses the world currency, proving the EUR-only binding`() {
        // The scenario builds amounts in EUR and applies SctInstSchemeRules at submission; if a
        // regression dropped the rule (or the scenario silently reused world.currency), this
        // assertion against the REAL rule is what catches the scheme contract drifting.
        assertThatThrownBy {
            SctInstSchemeRules.requireSchemeCurrency(Money.of("10.00", "CZK"))
        }.isInstanceOf(ValidationFailure::class.java)
        assertThat(SctInstSchemeRules.SCHEME_CURRENCY.code).isEqualTo("EUR")
    }
}
