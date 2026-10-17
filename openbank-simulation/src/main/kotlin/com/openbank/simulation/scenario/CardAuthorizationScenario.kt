// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One step of the card money path: a card authorization hold lifecycle modelled on the REAL
 * `openbank-card-processing-service` domain (ADR-0100 Layer 2) — the `CardAuthorization`
 * aggregate and the pure `AuthorizationLifecycle` policy (clear/reverse/expire, refusals as
 * values). The scenario reserves funds on the source account, places an APPROVED hold, then
 * drives one seeded presentment outcome:
 *
 *  - **full clearing** — the policy accepts, the capture posts a balanced double-entry journal
 *    as the real `JournalEntry` aggregate and projects it onto balances;
 *  - **partial clearing + reversal** — a partial presentment lands, the scheme reverses the
 *    remainder (what cleared stays cleared, the hold releases the rest);
 *  - **plain reversal** — the acquirer releases the hold without presenting;
 *  - **no presentment** — the hold expires on its TTL, released by a scheduled task through
 *    the real `AuthorizationLifecycle.expire`.
 *
 * An injected write fault on the capture post compensates before anything is durable: the hold
 * is reversed, nothing is posted, and the conservation invariant sees an authorization that
 * neither cleared nor moved money. Every choice (account, amount, channel, outcome, faults) is
 * drawn from the run's seeded RNG, so a step is a pure function of the seed and step index.
 */
object CardAuthorizationScenario {

    // Up to 15000.00 per authorization — deliberately above the 10000.00 opening balance, so a
    // card-presented amount the account cannot cover is reachable and the DECLINED path (which
    // holds and moves nothing) is exercised by the seeded sweep, not just by unit test.
    private const val MAX_MINOR_UNITS = 1_500_000L
    private const val MIN_MINOR_UNITS = 1L

    // A partial clearing needs at least 2 minor units so the split is strictly positive.
    private const val MIN_PARTIAL_SPLIT_MINOR_UNITS = 2L
    private const val MINOR_UNITS_DIGITS = 2
    private const val HOLD_TTL_HOURS = 48L
    private const val OUTCOME_BRANCHES = 4
    private val HOLD_TTL: Duration = Duration.ofHours(HOLD_TTL_HOURS)
    private val ZONE = ZoneId.of("Europe/Prague")

    // The card-scheme clearing GL account; the customer dimension is carried by
    // JournalLine.subAccountId, which is what bookedDeltas() projects on. The credit leg's
    // sub-account is a non-customer clearing identity, so the projection only moves the
    // cardholder's balance.
    private const val GL_ID_LOW_BITS = 7L
    private const val CLEARING_SUB_ACCOUNT_LOW_BITS = 8L
    private const val SYSTEM_ACTOR_LOW_BITS = 9L
    private val CARD_SCHEME_CLEARING_GL: UUID = UUID(0L, GL_ID_LOW_BITS)
    private val CLEARING_SUB_ACCOUNT: UUID = UUID(0L, CLEARING_SUB_ACCOUNT_LOW_BITS)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LOW_BITS)

    fun step(world: World) {
        val random = world.context.random
        val accounts = world.customerAccounts

        val amountMinor = random.nextLong(MIN_MINOR_UNITS, MAX_MINOR_UNITS)
        val sourceIdx = random.nextInt(accounts.size)
        val source = AccountCurrency(accounts[sourceIdx], world.currency)
        val now = world.context.clock.instant()

        // 1. Funds check: a floor breach is a legitimate DECLINED, recorded like production
        //    records declines — it holds nothing and moves nothing.
        try {
            world.balances.put(world.balances.get(source).withReservation(minorToMajor(amountMinor)))
        } catch (_: IllegalArgumentException) {
            val declined = newAuthorization(world, source, amountMinor, now, AuthorizationStatus.DECLINED)
            world.cardAuths.place(declined)
            world.audit.append("card auth ${declined.id} DECLINED: insufficient funds on $source")
            return
        }

        val authorization = newAuthorization(world, source, amountMinor, now, AuthorizationStatus.APPROVED)
        world.cardAuths.place(authorization)
        world.audit.append("card auth ${authorization.id} APPROVED: holds $amountMinor minor on $source")

        // 2. The hold's expiry is a scheduled task, exactly like production's expiry sweep:
        //    when it fires, the real AuthorizationLifecycle.expire decides (and refuses an
        //    early expiry — the policy, not the caller, owns that boundary).
        world.context.scheduler.schedule(HOLD_TTL) { expireHold(world, authorization.id) }

        // 3. Seeded presentment outcome.
        present(world, authorization.id, amountMinor)
    }

    /** Drives one seeded outcome: full clear / partial clear + reversal / reversal / leave for expiry. */
    private fun present(world: World, authorizationId: UUID, amountMinor: Long) {
        when (world.context.random.nextInt(OUTCOME_BRANCHES)) {
            0 -> clear(world, authorizationId, amountMinor)
            1 -> {
                if (amountMinor < MIN_PARTIAL_SPLIT_MINOR_UNITS) {
                    clear(world, authorizationId, amountMinor)
                } else {
                    clear(world, authorizationId, world.context.random.nextLong(MIN_MINOR_UNITS, amountMinor))
                    releaseHold(world, authorizationId)
                }
            }
            2 -> releaseHold(world, authorizationId)
            else -> {
                // No presentment: the scheduled expiry task releases the hold when the
                // scheduler drains after this scenario.
            }
        }
    }

    private fun newAuthorization(
        world: World,
        source: AccountCurrency,
        amountMinor: Long,
        now: Instant,
        status: AuthorizationStatus,
    ): CardAuthorization {
        val random = world.context.random
        return CardAuthorization(
            id = random.nextUuid(),
            cardId = random.nextUuid(),
            accountId = source.accountId,
            partyId = random.nextUuid(),
            amountMinorUnits = amountMinor,
            currencyCode = source.currency,
            channel = random.pick(PresentmentChannel.entries.toList()),
            mcc = null,
            merchantName = null,
            merchantCountry = null,
            status = status,
            category = "uncategorized",
            declineReason = if (status == AuthorizationStatus.DECLINED) "INSUFFICIENT_FUNDS" else null,
            clearedAmountMinorUnits = 0,
            networkReference = null,
            authorizedAt = now,
            expiresAt = now.plus(HOLD_TTL),
            updatedAt = now,
        )
    }

    /**
     * Applies one presentment (a clearing) against [authorizationId] through the real policy.
     * The aggregate and the book's posted total are only advanced after the capture journal is
     * durable, so an injected write fault leaves the book and the ledger in lockstep; the
     * compensation path then reverses the hold and nothing has moved.
     */
    private fun clear(world: World, authorizationId: UUID, amountMinor: Long) {
        val authorization = world.cardAuths.get(authorizationId) ?: return
        val outcome = AuthorizationLifecycle.clear(
            authorization,
            amountMinor,
            world.currency,
            world.context.clock,
        )
        when (outcome) {
            is PresentmentOutcome.Refused -> {
                // The policy refused (e.g. a re-presentment after the hold was already
                // released). A refusal is an ordinary event in scheme traffic, not a defect.
                world.audit.append("card auth $authorizationId presentment refused: ${outcome.reason}")
                return
            }
            is PresentmentOutcome.Accepted -> {
                val cleared = outcome.authorization
                try {
                    if (world.context.faults.shouldFailWrite()) {
                        throw SimulatedWriteFailure("capture post failed for $authorizationId")
                    }
                    val entry = buildCapture(world, cleared, amountMinor)
                    world.ledger.post("card-auth-$authorizationId-clearing", entry)
                    world.cardAuths.replace(cleared)
                    world.cardAuths.recordPosted(authorizationId, amountMinor)
                    publishBooked(world, entry)
                    // The capture converts hold to booked: the cleared portion leaves the
                    // reservation (the booked debit now covers it). Any remainder stays held
                    // until a reversal or the expiry task releases it.
                    val source = AccountCurrency(authorization.accountId, authorization.currencyCode)
                    world.balances.put(
                        world.balances.get(source).releaseReservation(minorToMajor(amountMinor)),
                    )
                    world.audit.append(
                        "card auth $authorizationId -> ${cleared.status}: cleared $amountMinor minor",
                    )
                } catch (_: SimulatedWriteFailure) {
                    // Pre-commit fault: nothing is durable. Reverse the hold so the funds are
                    // released — the invariant then sees an authorization that neither cleared
                    // nor moved money, with the reservation fully returned.
                    world.audit.append("card auth $authorizationId capture post failed; compensating")
                    releaseHold(world, authorizationId)
                }
            }
        }
    }

    /**
     * Releases whatever the hold still holds — via the real `AuthorizationLifecycle.reverse`
     * for a live hold (partially cleared or approved), which keeps the cleared total intact
     * and releases only the remainder.
     */
    private fun releaseHold(world: World, authorizationId: UUID) {
        val authorization = world.cardAuths.get(authorizationId) ?: return
        val outcome = AuthorizationLifecycle.reverse(authorization, world.context.clock)
        if (outcome is PresentmentOutcome.Accepted) {
            val released = outcome.authorization
            world.cardAuths.replace(released)
            releaseRemainderToAvailable(world, authorization)
            world.audit.append("card auth $authorizationId -> REVERSED")
        }
    }

    private fun expireHold(world: World, authorizationId: UUID) {
        val authorization = world.cardAuths.get(authorizationId) ?: return
        val outcome = AuthorizationLifecycle.expire(authorization, world.context.clock)
        if (outcome is PresentmentOutcome.Accepted) {
            world.cardAuths.replace(outcome.authorization)
            releaseRemainderToAvailable(world, authorization)
            world.audit.append("card auth $authorizationId -> EXPIRED: hold released")
        }
    }

    /** Returns the still-held remainder of [authorization] from the balance reservation. */
    private fun releaseRemainderToAvailable(world: World, authorization: CardAuthorization) {
        if (!authorization.status.holdsFunds) return
        val remainderMinor = authorization.heldAmountMinorUnits
        if (remainderMinor == 0L) return
        val key = AccountCurrency(authorization.accountId, authorization.currencyCode)
        world.balances.put(
            world.balances.get(key).releaseReservation(minorToMajor(remainderMinor)),
        )
        world.cardAuths.recordReleased(authorization.id, remainderMinor)
    }

    /** Build the real POSTED capture journal: debit cardholder, credit scheme clearing. */
    private fun buildCapture(world: World, authorization: CardAuthorization, amountMinor: Long): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val money = Money.of(minorToMajor(amountMinor), authorization.currencyCode)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val source = AccountCurrency(authorization.accountId, authorization.currencyCode)
        val debit = JournalLine(
            id = UUID(journalId.mostSignificantBits, 1L),
            journalId = journalId,
            glAccountId = CARD_SCHEME_CLEARING_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = source.accountId,
        )
        val credit = JournalLine(
            id = UUID(journalId.mostSignificantBits, 2L),
            journalId = journalId,
            glAccountId = CARD_SCHEME_CLEARING_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
            subAccountId = CLEARING_SUB_ACCOUNT,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = authorization.id,
            entryDate = today,
            valueDate = today,
            description = "card capture ${authorization.id}",
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    /** Project the entry's real `bookedDeltas()` (credit-positive) onto the bus. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }

    private fun minorToMajor(minorUnits: Long): BigDecimal = BigDecimal(minorUnits).movePointLeft(MINOR_UNITS_DIGITS)
}
