// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): exercises [ClearingCycleScenario]
 * directly against the REAL `clearing-service` domain aggregates, so the harness's binding to
 * the service is proven by an actual assertion, not merely by compiling.
 */
class ClearingCycleScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step settles the real ClearingBatch, its items and the SettlementPosition`() {
        val world = newWorld()
        ClearingCycleScenario.step(world)

        assertThat(world.clearingBatches).hasSize(1)
        assertThat(world.clearingItems).hasSize(3)
        assertThat(world.settlementPositions).hasSize(1)
        assertThat(world.clearingBatches.single().status).isEqualTo(ClearingStatus.SETTLED)
        assertThat(world.clearingItems.all { it.status == ClearingStatus.SETTLED }).isTrue()
        assertThat(world.clearingBatches.single().settledAt).isNotNull()

        // The batch totals carry the production bilateral-netting shape: totals equal the item
        // sum, credit mirrors debit, and the net position is fully offset.
        val batch = world.clearingBatches.single()
        assertThat(batch.itemCount).isEqualTo(3)
        assertThat(batch.totalCredit).isEqualByComparingTo(batch.totalDebit)
        assertThat(batch.netPosition).isEqualByComparingTo("0")
        val position = world.settlementPositions.single()
        assertThat(position.settled).isTrue()
        assertThat(position.grossDebit).isEqualByComparingTo(batch.totalDebit)
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault fails the ClearingBatch and every item together`() {
        // 100% write-failure rate forces the settlement-journal failure branch every time.
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        ClearingCycleScenario.step(world)

        assertThat(world.clearingBatches.single().status).isEqualTo(ClearingStatus.FAILED)
        assertThat(world.clearingItems.all { it.status == ClearingStatus.FAILED }).isTrue()
        // Nothing moved: no position was opened, every item carries the failure detail.
        assertThat(world.settlementPositions).isEmpty()
        assertThat(world.clearingItems.all { it.errorCode == "TECHNICAL_ERROR" }).isTrue()
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the ClearingBatch and every item`() {
        // 100% conflict rate forces the post-commit compensation branch every time.
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        ClearingCycleScenario.step(world)

        assertThat(world.clearingBatches.single().status).isEqualTo(ClearingStatus.REVERSED)
        assertThat(world.clearingItems.all { it.status == ClearingStatus.REVERSED }).isTrue()
        // The position never opened: the reversal happened before the happy-path settlement.
        assertThat(world.settlementPositions).isEmpty()
        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNull()
    }

    @Test
    fun `the invariant catches a batch whose totals drift from its items`() {
        // The mutation-style proof that clearingCompleteness is not vacuous: inflate a batch
        // total after the step and watch the invariant fail.
        val world = newWorld()
        ClearingCycleScenario.step(world)
        val batch = world.clearingBatches.single()
        world.clearingBatches[0] = batch.copy(totalDebit = batch.totalDebit.add(java.math.BigDecimal.ONE))

        assertThat(MoneyPathInvariants.clearingCompleteness.check(world)).isNotNull()
    }
}
