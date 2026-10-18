// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.simulation.scenario

import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.error.SctInstSchemeRules
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Money-path SCT Inst adoption: exercises [SctInstPaymentScenario] directly against the REAL
 * `sepa-instant` `SctInstPayment` aggregate and the REAL `SctInstSchemeRules` admission rule,
 * so the harness's binding to sepa-instant (beyond ledger/balance/SEPA/settlement) is proven by
 * an actual assertion, not merely by compiling — the same shape as [SepaSettlementScenarioTest].
 *
 * The forced-fault branches are deterministic (`FaultProfile(1.0)` fires before the seeded
 * timeout draw); the timeout and happy-path draws are located by scanning fixed seeds, so the
 * test stays green if the scenario's draw order changes.
 */
class SctInstPaymentScenarioTest {

    private fun newWorld(seed: Long, faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    @Test
    fun `the real scheme rule refuses a non-EUR amount — and admits EUR`() {
        assertThatThrownBy {
            SctInstSchemeRules.requireSchemeCurrency(Money.of(BigDecimal("100.00"), "CZK"))
        }.isInstanceOf(ValidationFailure::class.java)
            .hasMessageContaining("currency")

        // EUR admission is what the scenario relies on; prove it does not throw.
        SctInstSchemeRules.requireSchemeCurrency(Money.of(BigDecimal("100.00"), "EUR"))
    }

    @Test
    fun `a happy-path step settles the real SctInstPayment with one balanced EUR journal`() {
        val world = worldWithStatus(SctInstStatus.SETTLED)
        val payment = world.sctInstPayments.single()
        assertThat(payment.status).isEqualTo(SctInstStatus.SETTLED)
        assertThat(payment.settledAt).isNotNull()
        assertThat(payment.amount.currency.code).isEqualTo("EUR")
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sctInstCompleteness.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault rejects the payment before any money moved`() {
        // 100% write-failure rate forces the reject branch before the timeout draw.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(writeFailureRate = 1.0))
        SctInstPaymentScenario.step(world)

        val payment = world.sctInstPayments.single()
        assertThat(payment.status).isEqualTo(SctInstStatus.REJECTED)
        assertThat(payment.rejectReason).contains("TECHNICAL_ERROR")
        // the settlement journal never committed — nothing to unwind, nothing settled
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sctInstCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable journal and recalls the payment`() {
        // 100% lock-conflict rate forces the recall branch after the journal committed.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(lockConflictRate = 1.0))
        SctInstPaymentScenario.step(world)

        val payment = world.sctInstPayments.single()
        assertThat(payment.status).isEqualTo(SctInstStatus.RECALLED)
        assertThat(payment.recalledAt).isNotNull()
        // original + reversal both durable; the EUR legs still net to zero
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sctInstCompleteness.check(world)).isNull()
    }

    @Test
    fun `a scheme timeout unwinds the durable journal and leaves the payment terminal`() {
        val world = worldWithStatus(SctInstStatus.TIMEOUT)
        val payment = world.sctInstPayments.single()
        assertThat(payment.status).isEqualTo(SctInstStatus.TIMEOUT)
        // an instant payment that never settles must not keep its money movement
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sctInstCompleteness.check(world)).isNull()
    }

    /** Scan fixed seeds until [status] is drawn, so the test survives draw-order changes. */
    private fun worldWithStatus(status: SctInstStatus): World {
        for (seed in 0L..200L) {
            val world = newWorld(seed)
            SctInstPaymentScenario.step(world)
            if (world.sctInstPayments.single().status == status) return world
        }
        error("no seed in 0..200 produced $status — is TIMEOUT_RATE misconfigured?")
    }
}
