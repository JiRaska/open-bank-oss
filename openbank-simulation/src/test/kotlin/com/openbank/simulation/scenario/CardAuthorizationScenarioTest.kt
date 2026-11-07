// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Exercises [CardAuthorizationScenario] directly against the REAL card-processing domain
 * (`CardAuthorization`, `AuthorizationLifecycle`), so the harness's binding to the card money
 * path is proven by actual assertions — every step records exactly one authorization, every
 * outcome the scenario seeds (clearing, partial clearing + reversal, plain reversal, expiry,
 * decline) actually occurs across seeds, and once the scheduler drains (firing the hold's
 * expiry task) no reservation is left and the conservation invariant holds.
 */
class CardAuthorizationScenarioTest {

    private fun newWorld(seed: Long, faults: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faults), SimulationConfig())

    /** Run one fully-settled step: scenario, then the drain that fires the expiry task. */
    private fun settledStep(world: World) {
        CardAuthorizationScenario.step(world)
        world.context.scheduler.drain()
    }

    @Test
    fun `a single settled step records exactly one authorization and satisfies the invariant`() {
        val world = newWorld(seed = 42L)
        settledStep(world)

        assertThat(world.cardAuths.all()).hasSize(1)
        assertThat(MoneyPathInvariants.cardAuthorizationConservation.check(world)).isNull()
    }

    @Test
    fun `every seeded outcome occurs across seeds and no reservation survives a settled step`() {
        val observed = mutableSetOf<AuthorizationStatus>()
        var sawPartialReversal = false

        (0L until 60L).forEach { seed ->
            val step = runSeed(seed)
            observed.addAll(step.statuses)
            sawPartialReversal = sawPartialReversal || step.sawPartialReversal
            assertThat(step.reservationsAllReleased).withFailMessage("seed $seed left a reservation")
                .isTrue()
        }

        assertThat(observed).contains(
            AuthorizationStatus.CLEARED,
            AuthorizationStatus.REVERSED,
            AuthorizationStatus.EXPIRED,
            AuthorizationStatus.DECLINED,
        )
        assertThat(sawPartialReversal)
            .withFailMessage("no seed produced a partial clearing followed by a reversal")
            .isTrue()
    }

    private data class SeedObservation(
        val statuses: Set<AuthorizationStatus>,
        val sawPartialReversal: Boolean,
        val reservationsAllReleased: Boolean,
    )

    private fun runSeed(seed: Long): SeedObservation {
        val world = newWorld(seed)
        repeat(12) { settledStep(world) }

        val statuses = mutableSetOf<AuthorizationStatus>()
        var sawPartialReversal = false
        world.cardAuths.all().forEach { authorization ->
            statuses.add(authorization.status)
            if (authorization.status == AuthorizationStatus.REVERSED && authorization.clearedAmountMinorUnits > 0L) {
                sawPartialReversal = true
            }
            assertThat(authorization.status.isTerminal)
                .withFailMessage("authorization ${authorization.id} still ${authorization.status} after a settled step")
                .isTrue()
        }
        val released = world.balances.all().all { balance ->
            balance.reservedAmount.compareTo(BigDecimal.ZERO) == 0
        }
        assertThat(MoneyPathInvariants.cardAuthorizationConservation.check(world)).isNull()
        return SeedObservation(statuses, sawPartialReversal, released)
    }

    @Test
    fun `captured amounts land in the ledger and project onto the cardholder balance`() {
        val world = newWorld(seed = 7L)
        repeat(20) { settledStep(world) }

        val clearedMinor = world.cardAuths.all()
            .filter { it.status == AuthorizationStatus.CLEARED || it.status == AuthorizationStatus.REVERSED }
            .sumOf { it.clearedAmountMinorUnits }
        assertThat(clearedMinor).isPositive()

        // Conservation + projection invariants are the cross-check: the capture journals are
        // the only source of booked movement on customer accounts in this run, so a mismatch
        // between what the book says was captured and what the ledger recorded fails here.
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.projectionConsistency.check(world)).isNull()
    }

    @Test
    fun `injected write faults compensate without moving money or stranding the hold`() {
        (0L until 30L).forEach { seed ->
            val world = newWorld(seed, FaultProfile.ADVERSARIAL.copy(writeFailureRate = 0.5))
            repeat(10) { settledStep(world) }

            assertThat(MoneyPathInvariants.cardAuthorizationConservation.check(world))
                .withFailMessage("seed $seed: conservation broke under write faults")
                .isNull()
            world.balances.all().forEach { balance ->
                assertThat(balance.reservedAmount).isEqualByComparingTo(BigDecimal.ZERO)
            }
        }
    }
}
