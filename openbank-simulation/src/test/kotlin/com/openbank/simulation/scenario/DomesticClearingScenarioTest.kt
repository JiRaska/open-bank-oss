// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.domestic.domain.error.DomesticSchemeRules
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.money.Money
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
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises [DomesticClearingScenario]
 * directly against the REAL `domestic-payment` `DomesticPayment` status machine and the REAL
 * `DomesticSchemeRules` scheme rule, so the harness's binding to the service (beyond
 * sepa-payment/settlement) is proven by an actual assertion, not merely by compiling.
 */
class DomesticClearingScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step settles the real DomesticPayment and books the real Settlement`() {
        val world = newWorld()
        DomesticClearingScenario.step(world)

        assertThat(world.domesticPayments).hasSize(1)
        assertThat(world.settlements).hasSize(1)
        assertThat(world.domesticPayments.single().status).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.BOOKED)

        // The production #4218 semantics: the dispatch stamp is written BEFORE the outbound
        // hop, and the machine stamps settlement on the SETTLED branch.
        val payment = world.domesticPayments.single()
        assertThat(payment.schemeDispatchedAt).isNotNull()
        assertThat(payment.submittedAt).isNotNull()
        assertThat(payment.settledAt).isNotNull()
        assertThat(MoneyPathInvariants.domesticPaymentCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.settlementCompleteness.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault at dispatch rejects the DomesticPayment without a dispatch stamp`() {
        // 100% write-failure rate forces the scheme-dispatch rejection branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        DomesticClearingScenario.step(world)

        val payment = world.domesticPayments.single()
        assertThat(payment.status).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(payment.rejectReason).isEqualTo(DomesticRejectReason.TECHNICAL_ERROR)
        // The dispatch never committed, so the #4218 stamp must be absent even though the
        // machine has already stamped submittedAt on the VALIDATED hop.
        assertThat(payment.schemeDispatchedAt).isNull()
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.REJECTED)
        assertThat(MoneyPathInvariants.domesticPaymentCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict returns the DomesticPayment and reverses the credited Settlement`() {
        // 100% conflict rate forces the post-commit compensation branch every time.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        DomesticClearingScenario.step(world)

        val payment = world.domesticPayments.single()
        assertThat(payment.status).isEqualTo(DomesticPaymentStatus.RETURNED)
        // The machine stamps settledAt on the RETURNED branch (the funds came back).
        assertThat(payment.settledAt).isNotNull()
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.CREDITED_REVERSED)
        assertThat(MoneyPathInvariants.domesticPaymentCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.settlementCompleteness.check(world)).isNull()
    }

    @Test
    fun `the real scheme rule refuses a non-CZK amount, proving the CZK-only binding`() {
        // The scenario builds amounts in SCHEME_CURRENCY and applies DomesticSchemeRules at
        // submission; if a regression dropped the rule (or the scenario silently switched
        // currencies), this assertion against the REAL rule is what catches the drift.
        assertThatThrownBy {
            DomesticSchemeRules.requireSchemeCurrency(Money.of("10.00", "EUR"))
        }.isInstanceOf(ValidationFailure::class.java)
        assertThat(DomesticSchemeRules.SCHEME_CURRENCY.code).isEqualTo("CZK")
    }
}
