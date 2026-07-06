// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #267 (ADR-0100 full-service adoption): exercises [SepaSettlementScenario] directly
 * against the REAL `sepa-payment` `SepaPayment` and `settlement-service` `Settlement` domain
 * aggregates, so the harness's binding to those two services (beyond ledger/balance) is proven
 * by an actual assertion, not merely by compiling.
 */
class SepaSettlementScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step completes the real SepaPayment and books the real Settlement`() {
        val world = newWorld()
        SepaSettlementScenario.step(world)

        assertThat(world.sepaPayments).hasSize(1)
        assertThat(world.settlements).hasSize(1)
        assertThat(world.sepaPayments.single().status).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.BOOKED)
    }

    @Test
    fun `a write-failure fault rejects the SepaPayment and the Settlement together`() {
        // 100% write-failure rate forces the debit-settlement branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        SepaSettlementScenario.step(world)

        assertThat(world.sepaPayments.single().status).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.REJECTED)
    }

    @Test
    fun `a post-commit conflict returns the SepaPayment and reverses the credited Settlement`() {
        // 100% conflict rate forces the post-credit compensation branch every time.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        SepaSettlementScenario.step(world)

        assertThat(world.sepaPayments.single().status).isEqualTo(SepaPaymentStatus.RETURNED)
        assertThat(world.settlements.single().status).isEqualTo(SettlementStatus.CREDITED_REVERSED)
    }

    @Test
    fun `every step leaves both real aggregates in a terminal state, holding the ADR-0100 invariants`() {
        val world = newWorld(FaultProfile.ADVERSARIAL)
        repeat(25) { SepaSettlementScenario.step(world) }

        assertThat(MoneyPathInvariants.sepaPaymentCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.settlementCompleteness.check(world)).isNull()
        assertThat(world.sepaPayments).hasSize(25)
        assertThat(world.settlements).hasSize(25)
    }
}
