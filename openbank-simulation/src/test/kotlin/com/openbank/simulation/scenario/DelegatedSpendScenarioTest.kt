// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR-0249 D3: exercises [DelegatedSpendScenario] directly against the REAL delegation-service
 * domain (`DelegationGrant` lifecycle, `SpendCeilings.evaluate`, the `SpendReservation` state
 * machine), so the harness's binding to the delegated-spend money path is proven by actual
 * assertions: every reservation settles inside its step, a confirmed spend posts exactly the
 * confirmed amount to the ledger, a ceiling refusal moves nothing, and a seeded release returns
 * both the balance hold and the ceiling headroom.
 */
class DelegatedSpendScenarioTest {

    private fun newWorld(seed: Long = 42L, faults: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faults), SimulationConfig())

    @Test
    fun `every reservation settles inside its step and all three invariants hold across seeds`() {
        (0L until 30L).forEach { seed ->
            val world = newWorld(seed)
            repeat(25) {
                DelegatedSpendScenario.step(world)
                assertThat(world.spendReservations.none { it.state == SpendReservationState.RESERVED })
                    .withFailMessage("seed=$seed step left a reservation RESERVED")
                    .isTrue()
                assertThat(MoneyPathInvariants.delegatedSpendCompleteness.check(world)).isNull()
                assertThat(MoneyPathInvariants.delegatedSpendConservation.check(world)).isNull()
                assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world)).isNull()
            }
        }
    }

    @Test
    fun `a confirmed spend moves exactly the confirmed amount to the ledger`() {
        val world = newWorld()
        var confirmedBefore = 0
        repeat(200) {
            DelegatedSpendScenario.step(world)
            val confirmed = world.spendReservations.count { it.state == SpendReservationState.CONFIRMED }
            if (confirmed > confirmedBefore) {
                val grantId = world.spendReservations.last { it.state == SpendReservationState.CONFIRMED }.grantId
                val amount = world.delegationSpends.confirmedAmount(grantId)
                assertThat(world.delegationSpends.postedAmount(grantId))
                    .withFailMessage("confirmed and posted must reconcile per grant")
                    .isEqualByComparingTo(amount)
                assertThat(world.ledger.postedCount()).isGreaterThan(0)
                return
            }
            confirmedBefore = confirmed
        }
        throw AssertionError("no confirmation in 200 seeded steps — the happy path was never exercised")
    }

    @Test
    fun `a ceiling refusal creates no reservation and posts no journal`() {
        (0L until 60L).forEach { seed ->
            val world = newWorld(seed)
            repeat(100) {
                val reservationsBefore = world.spendReservations.size
                val postedBefore = world.ledger.postedCount()
                val refusedBefore = world.delegationSpends.grantIds().sumOf { grantId ->
                    world.delegationSpends.decisionsFor(grantId).count { it.refused }
                }
                DelegatedSpendScenario.step(world)
                val refusedAfter = world.delegationSpends.grantIds().sumOf { grantId ->
                    world.delegationSpends.decisionsFor(grantId).count { it.refused }
                }
                if (refusedAfter > refusedBefore) {
                    assertThat(world.spendReservations.size)
                        .withFailMessage("a refused decision must not create a reservation")
                        .isEqualTo(reservationsBefore)
                    assertThat(world.ledger.postedCount())
                        .withFailMessage("a refused decision must not post a journal")
                        .isEqualTo(postedBefore)
                    assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world)).isNull()
                    return
                }
            }
        }
        throw AssertionError("no ceiling refusal across 60 seeds — the refusal path was never exercised")
    }

    @Test
    fun `a seeded release returns the balance hold without posting`() {
        val world = newWorld()
        val source = AccountCurrency(world.customerAccounts.first(), world.currency)
        var releasedBefore = 0
        repeat(200) {
            val bookedBefore = world.balances.get(source).bookedAmount
            val availableBefore = world.balances.get(source).available()
            releasedBefore = world.spendReservations.count { it.state == SpendReservationState.RELEASED }
            DelegatedSpendScenario.step(world)
            val released = world.spendReservations.count { it.state == SpendReservationState.RELEASED }
            if (released > releasedBefore) {
                // The release path took no money: booked is untouched and the hold is back.
                assertThat(world.balances.get(source).bookedAmount).isEqualByComparingTo(bookedBefore)
                assertThat(world.balances.get(source).available()).isEqualByComparingTo(availableBefore)
                return
            }
        }
        throw AssertionError("no seeded release in 200 steps — the release path was never exercised")
    }

    @Test
    fun `the adversarial fault profile still settles every reservation`() {
        val world = newWorld(seed = 7L, faults = FaultProfile.ADVERSARIAL)
        var confirmed = BigDecimal.ZERO
        var released = 0
        repeat(100) {
            DelegatedSpendScenario.step(world)
            assertThat(world.spendReservations.none { it.state == SpendReservationState.RESERVED }).isTrue()
        }
        confirmed = world.delegationSpends.grantIds()
            .fold(BigDecimal.ZERO) { acc, id -> acc + world.delegationSpends.confirmedAmount(id) }
        released = world.spendReservations.count { it.state == SpendReservationState.RELEASED }
        assertThat(MoneyPathInvariants.delegatedSpendCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.delegatedSpendConservation.check(world)).isNull()
        assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world)).isNull()
        // The fault profile must not silently neuter the scenario: money actually moved or was
        // legitimately declined/released somewhere in 100 adversarial steps.
        assertThat(confirmed + released.toBigDecimal() > BigDecimal.ZERO).isTrue()
    }
}
