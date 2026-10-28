// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendCeilings
import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendWindows
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.DelegationSpendBook
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the delegated-spend money path (ADR-0249 D3): a delegate spends the grantor's
 * money under the grant's cumulative ceilings, driven through the **real `DelegationGrant`
 * lifecycle** (OFFERED → ACTIVE via [DelegationGrant.accept]), the **real `SpendCeilings.evaluate`
 * policy** (per-transaction | daily | monthly, Europe/Prague windows), and the **real
 * `SpendReservation` aggregate** (RESERVED → CONFIRMED / RELEASED, with its
 * `countsTowardCeilings` asymmetry: RESERVED and CONFIRMED both consume headroom, only
 * RELEASED gives it back) — posting the money through the **real `JournalEntry` aggregate**.
 * The ADR-0100 "build on the real system" convention, extending harness coverage to the
 * delegation rail (issue #667's E2E money-path family).
 *
 * Lifecycle subtleties mirrored from production, because a re-model would miss them:
 *  - The ceiling decision is made BEFORE the reservation exists: `evaluate` is asked with a
 *    [CountedSpend] computed from every reservation on the grant that still counts
 *    (RESERVED + CONFIRMED, [SpendReservation.countsTowardCeilings]), inside the real
 *    [SpendWindows] day/month windows and matching currency. A refusal is a legitimate
 *    outcome — recorded in the book so the invariant can replay the decision, nothing moves.
 *  - A reserved amount holds funds on the grantor's balance while the decision settles, and
 *    EVERY exit path releases that hold: ceiling refusal (no hold taken yet), balance floor
 *    decline, pre-commit write fault, post-commit conflict (after reversing the durable
 *    journal), and the seeded plain release — a delegate who did not spend must give the
 *    headroom back. `release()` refuses on a CONFIRMED reservation by construction, so a
 *    confirmed spend can never silently re-open the grantor's ceiling.
 *  - Only a CONFIRMED reservation records money on both sides (book confirmed == posted
 *    journals), reconciled per grant by `MoneyPathInvariants.delegatedSpendConservation`.
 */
object DelegatedSpendScenario {

    private const val MAX_AMOUNT_MINOR = 500_000L // up to 5000.00 — modest delegated purchases
    private const val NEW_GRANT_FRACTION = 10 // nextInt(10) < 3 ⇒ a 30% seeded chance to open a new grant
    private const val NEW_GRANT_THRESHOLD = 3
    private const val RELEASE_FRACTION = 4 // nextInt(4) == 0 ⇒ a 25% seeded release instead of a spend
    private const val RELEASE_MARK = 0
    private val ZONE: ZoneId = SpendWindows.ZONE

    // The same shared deposit-control GL the other scenarios post against; the customer
    // dimension is carried by JournalLine.subAccountId (what bookedDeltas() projects on).
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 2L)

    fun step(world: World) {
        val random = world.context.random
        val nowInstant = world.context.clock.instant()
        val now = OffsetDateTime.ofInstant(nowInstant, ZONE)

        // 1. Pick the grant to spend against: reuse an ACTIVE one, or offer + accept a new one
        //    through the real lifecycle (accept is the only OFFERED → ACTIVE path).
        val grant = when {
            world.delegationGrants.isEmpty() ||
                random.nextInt(NEW_GRANT_FRACTION) < NEW_GRANT_THRESHOLD -> createGrant(world, now)
                .also { world.delegationGrants.add(it) }

            else -> world.delegationGrants[random.nextInt(world.delegationGrants.size)]
        }

        // 2. The real counted spend: every reservation on this grant that still consumes
        //    headroom (RESERVED + CONFIRMED), inside the real day/month windows, same currency.
        val counted = countedSpend(world, grant.id, now)
        val amount = Money.of(
            BigDecimal(random.nextLong(1L, MAX_AMOUNT_MINOR)).movePointLeft(2),
            world.currency,
        )

        // 3. The ceiling decision happens BEFORE any reservation exists — the reserve path's
        //    contract (a check is advisory, a reservation is the authoritative claim).
        val decision = SpendCeilings.evaluate(grant, amount, counted, now)
        if (decision is SpendDecision.Refused) {
            world.delegationSpends.recordDecision(
                DelegationSpendBook.Decision(grant.id, UUID(0L, 0L), amount.amount, counted, refused = true),
            )
            world.audit.append("delegated spend refused on grant ${grant.id}: ${decision.reason}")
            return
        }

        // 4. Reserve through the real aggregate (its init enforces a valid idempotency key and
        //    a positive amount). RESERVED consumes headroom from this moment on.
        val reservation = SpendReservation(
            grantId = grant.id,
            amount = amount,
            idempotencyKey = "sim-delegated-spend-${random.nextUuid()}",
            operationType = com.openbank.delegation.domain.model.SpendReservationOperationType.DOMESTIC_PAYMENT,
            createdAt = now,
        )
        world.spendReservations.add(reservation)
        world.delegationSpends.recordDecision(
            DelegationSpendBook.Decision(grant.id, reservation.id, amount.amount, counted, refused = false),
        )
        settleReservation(world, reservation, now)
    }

    /**
     * Hold, move, and settle one RESERVED reservation. Every exit path releases the balance
     * hold: balance floor decline, pre-commit write fault, post-commit conflict (after
     * reversing the durable journal), a seeded plain release, and the happy path (the hold
     * settles into the debit the drain will project). Only the happy path confirms — and
     * `release()` refuses from CONFIRMED, so a confirmed spend can never re-open the ceiling.
     */
    private fun settleReservation(world: World, reservation: SpendReservation, now: OffsetDateTime) {
        val random = world.context.random
        val amount = reservation.amount
        val source = AccountCurrency(world.customerAccounts.first(), world.currency)
        val targetAccount = world.customerAccounts.first { it != source.accountId }
        val target = AccountCurrency(targetAccount, world.currency)

        // 5. Hold funds on the grantor's balance. A floor breach is a legitimate decline: the
        //    reservation is released through the real aggregate and no money moves.
        try {
            world.balances.put(world.balances.get(source).withReservation(amount.amount))
        } catch (_: IllegalArgumentException) {
            replaceReservation(world, reservation, reservation.release(now))
            world.audit.append("delegated spend ${reservation.id} declined: insufficient funds")
            return
        }

        // 6. Post the balanced double-entry journal as the real aggregate.
        val entry: JournalEntry
        try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("ledger post failed for delegated spend ${reservation.id}")
            }
            entry = buildTransfer(world, reservation, source, target, amount, world.context.clock.instant())
            world.ledger.post("delegated-spend-${reservation.id}", entry)
            publishBooked(world, entry)
        } catch (e: SimulatedWriteFailure) {
            // Nothing durable: give the hold and the headroom back.
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceReservation(world, reservation, reservation.release(now))
            world.audit.append("delegated spend ${reservation.id} compensated: ${e.message}")
            return
        }

        // 7. A post-commit fault forces the same compensation AFTER the journal is durable:
        //    reverse the journal, release the hold, release the reservation.
        if (world.context.faults.shouldConflict()) {
            val reversal = world.ledger.reverse(
                entry.id,
                random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { random.nextUuid() },
            )
            publishBooked(world, reversal)
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceReservation(world, reservation, reservation.release(now))
            world.audit.append("delegated spend ${reservation.id} compensated: post-commit conflict")
            return
        }

        // 8a. Seeded release: the delegate did not spend. The hold returns to available balance
        //     and the real release gives the ceiling headroom back (settledAt is stamped).
        if (random.nextInt(RELEASE_FRACTION) == RELEASE_MARK) {
            world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
            replaceReservation(world, reservation, reservation.release(now))
            world.audit.append("delegated spend ${reservation.id} released before settlement")
            return
        }

        // 8b. Happy path: the hold settles into the debit the drain will project from the posted
        //     journal; then the reservation is confirmed through the real aggregate.
        world.balances.put(world.balances.get(source).releaseReservation(amount.amount))
        replaceReservation(world, reservation, reservation.confirm(now))
        world.delegationSpends.recordConfirmed(reservation.grantId, amount.amount)
        world.delegationSpends.recordPosted(reservation.grantId, amount.amount)
        world.audit.append("delegated spend ${reservation.id} confirmed on grant ${reservation.grantId}")
    }

    /**
     * The real `CountedSpend` the reserve path computes: every reservation on [grantId] whose
     * [SpendReservation.countsTowardCeilings] is true (RESERVED + CONFIRMED; RELEASED gives the
     * headroom back), whose `createdAt` falls inside the corresponding [SpendWindows] window,
     * and whose currency matches the world's.
     */
    private fun countedSpend(world: World, grantId: UUID, now: OffsetDateTime): CountedSpend {
        val window = SpendWindows.windowAt(now)
        val zero = Money.zero(world.currency)
        var withinDay = zero
        var withinMonth = zero
        world.spendReservations
            .filter {
                it.grantId == grantId &&
                    it.countsTowardCeilings &&
                    it.amount.currency == com.openbank.libs.domain.money.CurrencyCode.of(world.currency)
            }
            .forEach { reservation ->
                if (!reservation.createdAt.isBefore(window.dayStart)) {
                    withinDay += reservation.amount
                }
                if (!reservation.createdAt.isBefore(window.monthStart)) {
                    withinMonth += reservation.amount
                }
            }
        return CountedSpend(withinDay = withinDay, withinMonth = withinMonth)
    }

    private fun replaceReservation(world: World, old: SpendReservation, new: SpendReservation) {
        val index = world.spendReservations.indexOfFirst { it.id == old.id }
        world.spendReservations[index] = new
    }

    /**
     * Offer + accept a fresh grant through the real lifecycle. Ceilings stay comfortably below
     * the seeded opening book so the ceiling (not the balance floor) is what refuses a seeded
     * share of attempts; the daily/monthly windows are wide enough that most steps decide on
     * the per-transaction ceiling.
     */
    private fun createGrant(world: World, now: OffsetDateTime): DelegationGrant {
        val random = world.context.random
        val offered = DelegationGrant(
            grantorPartyId = random.nextUuid(),
            granteePartyId = random.nextUuid(),
            resourceType = DelegationResourceType.ACCOUNT,
            resourceId = world.customerAccounts.first(),
            capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
            perTransactionLimit = Money.of(BigDecimal("5000.00"), world.currency),
            dailyLimit = Money.of(BigDecimal("12000.00"), world.currency),
            monthlyLimit = Money.of(BigDecimal("40000.00"), world.currency),
            validFrom = now.minusDays(1),
            validTo = now.plusYears(1),
            createdAt = now,
            updatedAt = now,
        )
        return offered.accept(random.nextUuid(), now)
    }

    /** Build the real POSTED double-entry transfer: debit grantor, credit the payee (deposit-control). */
    private fun buildTransfer(
        world: World,
        reservation: SpendReservation,
        source: AccountCurrency,
        target: AccountCurrency,
        money: Money,
        now: Instant,
    ): JournalEntry {
        val random = world.context.random
        val journalId = random.nextUuid()
        val today = LocalDate.ofInstant(now, ZONE)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = DEPOSIT_CONTROL_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = source.accountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = DEPOSIT_CONTROL_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = target.accountId,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = reservation.id,
            entryDate = today,
            valueDate = today,
            description = "delegated spend ${reservation.id} on grant ${reservation.grantId}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = now,
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** Project the entry's real `bookedDeltas()` (credit-positive, deposit-control) onto the bus. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }
}
