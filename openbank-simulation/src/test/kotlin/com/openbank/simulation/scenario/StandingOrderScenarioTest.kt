// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.StandingOrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The standing-order sweep scenario driven directly against the REAL
 * `com.openbank.standingorder.domain.model.StandingOrder` status machine, mirroring
 * [SepaSettlementScenarioTest]'s "real aggregate, real assertions" convention (issue #267 /
 * ADR-0100): every claim below is an assertion over the production domain object, not over a
 * harness re-model.
 */
class StandingOrderScenarioTest {

    private fun newWorld(faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed = 42L, faultProfile), SimulationConfig())

    @Test
    fun `a happy-path step claims the slot and confirms the execution with money moved`() {
        val world = newWorld()
        StandingOrderScenario.step(world)
        world.context.scheduler.drain()

        assertThat(world.standingOrders).hasSize(1)
        val order = world.standingOrders.single()
        assertThat(order.executionCount).isEqualTo(1)
        assertThat(world.standingOrdersBook.confirmedCount(order.id)).isEqualTo(1)
        // Money actually moved: one balanced journal (two lines) is posted, and the invariant
        // sweep over the real status machine plus the book holds.
        assertThat(world.ledger.postedCount()).isEqualTo(1)
        assertThat(MoneyPathInvariants.standingOrderCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a write-failure fault leaves no journal and records the failure on the status machine`() {
        val world = newWorld(FaultProfile(writeFailureRate = 1.0))
        StandingOrderScenario.step(world)
        world.context.scheduler.drain()

        val order = world.standingOrders.single()
        assertThat(order.failureCount).isEqualTo(1)
        assertThat(order.status).isEqualTo(StandingOrderStatus.ACTIVE)
        assertThat(world.standingOrdersBook.attemptedFailureCount(order.id)).isEqualTo(1)
        assertThat(world.standingOrdersBook.machineAcknowledgedFailureCount(order.id)).isEqualTo(1)
        assertThat(world.ledger.postedCount()).isZero()
        assertThat(MoneyPathInvariants.standingOrderCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable journal and records the failure`() {
        val world = newWorld(FaultProfile(lockConflictRate = 1.0))
        StandingOrderScenario.step(world)
        world.context.scheduler.drain()

        val order = world.standingOrders.single()
        assertThat(order.failureCount).isEqualTo(1)
        // Original + reversal are both durable, so conservation still nets to zero.
        assertThat(world.ledger.postedCount()).isEqualTo(2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.standingOrderCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `a ONCE order completes on confirm while a recurring order keeps its next date`() {
        // Seeded exploration over both frequencies: with enough independent worlds at least one
        // of each shape is exercised, and every one of them must satisfy the invariant sweep.
        var sawOnce = false
        var sawRecurring = false
        for (seed in 0L until 40L) {
            val world = World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())
            repeat(6) {
                StandingOrderScenario.step(world)
                world.context.scheduler.drain()
            }
            world.standingOrders.forEach { order ->
                when (order.frequency) {
                    com.openbank.standingorder.domain.model.Frequency.ONCE ->
                        sawOnce = sawOnce || order.status == StandingOrderStatus.COMPLETED
                    else ->
                        sawRecurring = sawRecurring || order.nextExecutionDate != null
                }
            }
            assertThat(MoneyPathInvariants.standingOrderCompleteness.check(world)).isNull()
            assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
        }
        assertThat(sawOnce).isTrue()
        assertThat(sawRecurring).isTrue()
    }

    @Test
    fun `adversarial faults keep every order reconciled with the book across 25 steps`() {
        val world = newWorld(FaultProfile.ADVERSARIAL)
        repeat(25) {
            StandingOrderScenario.step(world)
            world.context.scheduler.drain()
            assertThat(MoneyPathInvariants.standingOrderCompleteness.check(world)).isNull()
        }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }
}
