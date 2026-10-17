// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.StandingOrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exercises [StandingOrderExecutionScenario] directly, proving it actually feeds
 * [World.standingOrders] (so `MoneyPathInvariants.standingOrderExecutionConservation` is
 * load-bearing, not vacuously true over an always-empty book), that the journals it posts are
 * real, balanced `openbank-ledger-service` `JournalEntry` postings, and that the real
 * `StandingOrder` aggregate's lifecycle rules (daily recurrence, endDate completion, ONCE
 * completion after confirmation, FAILED after three consecutive failures) are all reached by a
 * seeded run.
 */
class StandingOrderExecutionScenarioTest {

    private fun newWorld(seed: Long = 42L, profile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, profile), SimulationConfig())

    @Test
    fun `a step places and executes a due order, posting one balanced journal`() {
        val world = newWorld()
        repeat(3) { StandingOrderExecutionScenario.step(world) }

        assertThat(world.standingOrders.all()).isNotEmpty()
        assertThat(world.ledger.postedCount()).isGreaterThanOrEqualTo(1)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(world)).isNull()
    }

    @Test
    fun `confirmed executions post exactly the order amount per execution date`() {
        val world = newWorld(seed = 7L)
        repeat(60) { StandingOrderExecutionScenario.step(world) }

        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(world)).isNull()
        // At least one execution must have confirmed, or the conservation law above proved nothing.
        val confirmedTotal = world.standingOrders.all()
            .sumOf { world.standingOrders.view(it.id).confirmedDates.size }
        assertThat(confirmedTotal).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `the run reaches the aggregate lifecycle end states — completed or failed orders exist`() {
        // A drained account eventually declines its daily order three sweeps in a row; short-lived
        // recurring orders run past their endDate. Either way the aggregate's own rules, not the
        // scenario's, drive the status — 400 steps over 4 accounts with 2000.00-max orders against
        // a 10000.00 opening balance forces at least one of those outcomes per fixed seed.
        val world = newWorld(seed = 11L)
        repeat(400) { StandingOrderExecutionScenario.step(world) }

        val statuses = world.standingOrders.all().map { it.status }.toSet()
        assertThat(statuses)
            .containsAnyOf(StandingOrderStatus.COMPLETED, StandingOrderStatus.FAILED)
        assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(world)).isNull()
        assertThat(MoneyPathInvariants.noNegativeBalance.check(world)).isNull()
    }

    @Test
    fun `a ONCE order completes after its single confirmed execution and is never re-due`() {
        // ONCE placement is a 0.20-of-0.60 Bernoulli draw per step, so a single seed can lawfully
        // create none; sweeping a fixed seed range keeps the test deterministic while guaranteeing
        // the shape is exercised somewhere in it.
        val worlds = (0L..9L).map { seed ->
            newWorld(seed = seed).also { world -> repeat(200) { StandingOrderExecutionScenario.step(world) } }
        }

        val onceOrders = worlds.flatMap { it.standingOrders.all() }.filter { it.frequency == Frequency.ONCE }
        assertThat(onceOrders).isNotEmpty()
        onceOrders.forEach { order ->
            assertThat(order.executionCount)
                .describedAs("ONCE order %s must not be re-swept after completion", order.id)
                .isLessThanOrEqualTo(1)
            val world = worlds.first { it.standingOrders.view(order.id).order != null }
            if (world.standingOrders.view(order.id).confirmedDates.isNotEmpty()) {
                assertThat(order.status).isEqualTo(StandingOrderStatus.COMPLETED)
            }
        }
        worlds.forEach { world ->
            assertThat(MoneyPathInvariants.standingOrderExecutionConservation.check(world)).isNull()
        }
    }

    @Test
    fun `adversarial faults keep every Layer-3 invariant green across a seeded run`() {
        // The runner drains the scheduler after EVERY scenario (its KDoc's "settled before the
        // invariant sweep" convention); a fault-injected run must mirror that or deferred event
        // deliveries look like mid-flight state.
        val world = newWorld(seed = 5L, profile = FaultProfile.ADVERSARIAL)
        repeat(150) {
            StandingOrderExecutionScenario.step(world)
            world.context.scheduler.drain()
        }

        MoneyPathInvariants.ALL.forEach { invariant ->
            assertThat(invariant.check(world))
                .describedAs("invariant %s must hold after a fault-injected standing-order run", invariant.name)
                .isNull()
        }
    }

    @Test
    fun `is deterministic — the same seed produces the same postings and order states`() {
        val first = newWorld(seed = 123L)
        repeat(60) { StandingOrderExecutionScenario.step(first) }

        val second = newWorld(seed = 123L)
        repeat(60) { StandingOrderExecutionScenario.step(second) }

        assertThat(first.ledger.postedCount()).isEqualTo(second.ledger.postedCount())
        assertThat(first.standingOrders.all().map { it.id to it.status })
            .isEqualTo(second.standingOrders.all().map { it.id to it.status })
    }
}
