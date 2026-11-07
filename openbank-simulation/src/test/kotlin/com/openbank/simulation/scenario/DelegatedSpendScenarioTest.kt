// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.DelegationSpendBook
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

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
    fun `a seeded release returns the balance hold with zero net movement`() {
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

    @Test
    fun `stale counted input is rejected against actual pre-decision reservation history`() {
        val world = newWorld()
        val at = OffsetDateTime.parse("2026-03-15T12:00:00+01:00")
        val grant = ceilingGrant(world, at)
        world.delegationGrants.add(grant)
        world.spendReservations.add(historyReservation(grant.id, "100.00", at))
        val history = DelegationSpendBook.HistorySnapshot.capture(world, grant.id, world.currency, at)
        world.delegationSpends.recordDecision(
            DelegationSpendBook.Decision(
                grant.id,
                UUID(0L, 10L),
                BigDecimal("50.00"),
                CountedSpend(Money.zero(world.currency), Money.zero(world.currency)),
                world.currency,
                history,
                refused = false,
            ),
        )
        assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world))
            .withFailMessage("a policy agreeing with stale zero usage is not independent evidence")
            .isNotNull()
    }

    @Test
    fun `same-instant later reservation and release do not rewrite earlier decision history`() {
        val world = newWorld()
        val at = OffsetDateTime.parse("2026-03-15T12:00:00+01:00")
        val grant = ceilingGrant(world, at)
        world.delegationGrants.add(grant)
        val prior = historyReservation(grant.id, "100.00", at)
        world.spendReservations.add(prior)
        val history = DelegationSpendBook.HistorySnapshot.capture(world, grant.id, world.currency, at)
        world.spendReservations[0] = prior.release(at)
        world.spendReservations.add(historyReservation(grant.id, "75.00", at))
        world.delegationSpends.recordDecision(
            DelegationSpendBook.Decision(
                grant.id,
                UUID(0L, 10L),
                BigDecimal("50.00"),
                CountedSpend(Money.of("100.00", world.currency), Money.of("100.00", world.currency)),
                world.currency,
                history,
                refused = true,
            ),
        )
        assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world)).isNull()
        assertThat(history.reservations).hasSize(1)
        assertThat(history.reservations.single().state).isEqualTo(SpendReservationState.RESERVED)
    }

    @Test
    fun `history reconstruction filters grant currency active state and Prague day month windows`() {
        val world = newWorld()
        val at = OffsetDateTime.parse("2026-03-15T12:00:00+01:00")
        val grant = ceilingGrant(world, at).copy(
            dailyLimit = Money.of("1000.00", world.currency),
            monthlyLimit = Money.of("2000.00", world.currency),
        )
        world.delegationGrants.add(grant)
        world.spendReservations.addAll(
            listOf(
                historyReservation(grant.id, "100.00", at),
                historyReservation(grant.id, "50.00", at.minusDays(1)).confirm(at.minusDays(1)),
                historyReservation(grant.id, "500.00", at.minusMonths(1)),
                historyReservation(grant.id, "700.00", at, "USD"),
                historyReservation(grant.id, "800.00", at).release(at),
                historyReservation(grant.id, "900.00", at.plusSeconds(1)),
                historyReservation(UUID(0L, 999L), "1000.00", at),
            ),
        )
        val history = DelegationSpendBook.HistorySnapshot.capture(world, grant.id, world.currency, at)
        assertThat(history.reservations).hasSize(5)
        assertThat(
            history.reservations.all {
                it.grantId == grant.id && it.amount.currency.code == world.currency
            },
        ).isTrue()
        world.delegationSpends.recordDecision(
            DelegationSpendBook.Decision(
                grant.id,
                UUID(0L, 10L),
                BigDecimal("50.00"),
                CountedSpend(Money.of("100.00", world.currency), Money.of("150.00", world.currency)),
                world.currency,
                history,
                refused = false,
            ),
        )
        assertThat(MoneyPathInvariants.delegationCeilingIntegrity.check(world)).isNull()
    }

    @Test
    fun `captured reservation values and collection cannot be rewritten by later world changes`() {
        val world = newWorld()
        val at = OffsetDateTime.parse("2026-03-15T12:00:00+01:00")
        val reservation = historyReservation(UUID(0L, 5L), "100.00", at)
        world.spendReservations.add(reservation)
        val history = DelegationSpendBook.HistorySnapshot.capture(world, reservation.grantId, world.currency, at)
        world.spendReservations[0] = reservation.copy(amount = Money.of("1.00", world.currency))
        world.spendReservations.clear()
        assertThat(history.reservations.single().amount.amount).isEqualByComparingTo("100.00")
        assertThat(history.reservations.single().createdAt).isEqualTo(at)
        assertThrows<UnsupportedOperationException> {
            (history.reservations as MutableList<SpendReservation>).clear()
        }
    }

    private fun ceilingGrant(world: World, at: OffsetDateTime): DelegationGrant = DelegationGrant(
        id = UUID(0L, 1L),
        grantorPartyId = UUID(0L, 2L),
        granteePartyId = UUID(0L, 3L),
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = world.customerAccounts.first(),
        capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
        dailyLimit = Money.of("100.00", world.currency),
        monthlyLimit = Money.of("100.00", world.currency),
        validFrom = at.minusDays(1),
        validTo = at.plusDays(1),
        createdAt = at,
        updatedAt = at,
    )

    private fun historyReservation(
        grantId: UUID,
        amount: String,
        at: OffsetDateTime,
        currency: String = "CZK",
    ): SpendReservation = SpendReservation(
        grantId = grantId,
        amount = Money.of(amount, currency),
        idempotencyKey = "history-regression",
        createdAt = at,
    )

    @Test
    fun `the seeded post-write release retains a journal and reversal with zero net movement`() {
        val world = newWorld()
        repeat(300) {
            DelegatedSpendScenario.step(world)
            if (world.audit.events().last().endsWith("released before settlement")) {
                val reservation = world.spendReservations.last()
                val entries = world.ledger.entriesByTransaction().getValue(reservation.id)
                assertThat(reservation.state).isEqualTo(SpendReservationState.RELEASED)
                assertThat(entries).hasSize(2)
                assertThat(entries.count { it.reversalOf != null }).isEqualTo(1)
                val deltas = entries.flatMap { it.bookedDeltas() }.groupBy { it.accountId to it.currency }
                assertThat(deltas.values.all { legs -> legs.sumOf { it.delta }.signum() == 0 }).isTrue()
                assertThat(MoneyPathInvariants.delegatedSpendConservation.check(world)).isNull()
                return
            }
        }
        throw AssertionError("no explicit seeded post-write release in 300 steps")
    }

    @Test
    fun `balanced half amount cannot hide behind full confirmed and posted books`() {
        val source = newWorld()
        repeat(300) {
            DelegatedSpendScenario.step(source)
            val reservation = source.spendReservations.lastOrNull()
            if (reservation != null &&
                reservation.state == SpendReservationState.CONFIRMED &&
                reservation.amount.amount > BigDecimal.ONE
            ) {
                val original = source.ledger.entriesByTransaction().getValue(reservation.id).single()
                val half = Money.of(
                    reservation.amount.amount.divide(BigDecimal("2")).setScale(2, RoundingMode.DOWN),
                    reservation.amount.currency.code,
                )
                val world = newWorld()
                world.delegationGrants.add(source.delegationGrants.first { it.id == reservation.grantId })
                world.spendReservations.add(reservation)
                world.delegationSpends.recordConfirmed(reservation.grantId, reservation.amount.amount)
                world.delegationSpends.recordPosted(reservation.grantId, reservation.amount.amount)
                world.ledger.post(
                    "delegated-spend-${reservation.id}",
                    original.copy(lines = original.lines.map { it.copy(amount = half, baseAmount = half) }),
                )
                assertThat(world.ledger.lineNetByCurrency().values.all { it.signum() == 0 }).isTrue()
                assertThat(world.delegationSpends.confirmedAmount(reservation.grantId))
                    .isEqualByComparingTo(world.delegationSpends.postedAmount(reservation.grantId))
                assertThat(MoneyPathInvariants.delegatedSpendConservation.check(world)).isNotNull()
                return
            }
        }
        throw AssertionError("no confirmed reservation above one currency unit in 300 steps")
    }
}
