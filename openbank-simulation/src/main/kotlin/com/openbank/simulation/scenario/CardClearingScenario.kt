// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.policy.AuthorizationLifecycle
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of the card-presentment
 * path, modelled directly against the **real `card-processing` domain** — the `CardAuthorization`
 * aggregate (amounts in minor units, the derived `heldAmountMinorUnits`/`effectiveSpendMinorUnits`
 * holds) and the pure `AuthorizationLifecycle` policy (`clear`/`reverse`/`expire`, refusals as
 * values, not exceptions) — not a re-model of either, the same way [DomesticClearingScenario]
 * builds on the real `DomesticPayment` aggregate (ADR-0100). The domain package is framework-free
 * (ADR-0002).
 *
 * A step mirrors the production ladder: authorise a card payment (a write fault records a real
 * DECLINED with a reason — the status exists precisely because a decline must be answerable
 * later), then present against the hold through the REAL [AuthorizationLifecycle.clear]: a full
 * presentment clears it (CLEARED), a partial one leaves it PARTIALLY_CLEARED until the scheme
 * reversal releases the remainder (REVERSED — the cleared total deliberately survives, real
 * money left), a silent acquirer lets the hold expire (EXPIRED), or the hold simply stays open
 * (legitimate — an APPROVED hold before its `expiresAt` is not money-in-flight). An injected
 * post-commit conflict after a full presentment forces the compensation branch: the reversal
 * releases the hold, and the clearing that already posted stays posted.
 */
object CardClearingScenario {

    private const val MAX_AMOUNT_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val HOLD_WINDOW_HOURS = 24L * 7

    // ≥ MIN_AMOUNT so a partial presentment (amount / 2, ≥ 1) never equals the full amount.
    private const val MIN_AMOUNT_MINOR_UNITS = 10L

    private val CHANNELS = PresentmentChannel.entries

    fun step(world: World) {
        val random = world.context.random
        val now = world.context.clock.instant()
        val action = Action.entries[random.nextInt(Action.entries.size)]

        // 1. Authorise. An injected write fault means the authorisation never committed: the
        //    decline is RECORDED (the real status exists for exactly this — a refused customer
        //    must be answerable from the row, not a log line).
        val authorized = try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("card authorization failed")
            }
            newAuthorization(world, action, now)
        } catch (e: SimulatedWriteFailure) {
            val declined = newAuthorization(world, action, now).copy(
                status = AuthorizationStatus.DECLINED,
                declineReason = e.message,
                updatedAt = now,
            )
            world.cardAuthorizations.add(declined)
            world.audit.append("card ${declined.id} declined: ${e.message}")
            return
        }
        world.cardAuthorizations.add(authorized)
        world.audit.append("card ${authorized.id} approved: ${authorized.amountMinorUnits}")

        // 2. Present against the hold through the REAL AuthorizationLifecycle policy.
        when (action) {
            Action.FULL_CLEAR -> presentInFull(world, authorized)
            Action.PARTIAL_CLEAR_THEN_REVERSE -> presentPartiallyThenReverse(world, authorized)
            Action.EXPIRE -> expire(world, authorized)
            // A hold the acquirer never presented against stays open — legitimately, until its
            // own expiresAt; the completeness invariant fails it only once that passes.
            Action.LEAVE_HOLDING -> Unit
        }
    }

    private fun newAuthorization(world: World, action: Action, now: Instant): CardAuthorization {
        val random = world.context.random
        // ≥ 10 minor units so a partial presentment (amount / 2, ≥ 1) never equals the full amount.
        val amount = random.nextLong(MIN_AMOUNT_MINOR_UNITS, MAX_AMOUNT_MINOR_UNITS)
        return CardAuthorization(
            id = random.nextUuid(),
            cardId = random.nextUuid(),
            accountId = world.customerAccounts[random.nextInt(world.customerAccounts.size)],
            partyId = random.nextUuid(),
            amountMinorUnits = amount,
            currencyCode = world.currency,
            channel = CHANNELS[random.nextInt(CHANNELS.size)],
            mcc = "5411",
            merchantName = "Simulated Merchant",
            merchantCountry = "CZ",
            status = AuthorizationStatus.APPROVED,
            category = "RETAIL",
            declineReason = null,
            clearedAmountMinorUnits = 0,
            networkReference = "ref-${random.nextUuid()}",
            authorizedAt = now,
            // The EXPIRE action needs a hold whose expiry has already passed; every other action
            // keeps a realistic week-long window, so an APPROVED hold is never spuriously stale.
            expiresAt = if (action == Action.EXPIRE) now else now.plus(HOLD_WINDOW_HOURS, ChronoUnit.HOURS),
            updatedAt = now,
        )
    }

    private fun presentInFull(world: World, authorized: CardAuthorization) {
        val outcome = AuthorizationLifecycle.clear(
            authorized,
            authorized.amountMinorUnits,
            authorized.currencyCode,
            world.context.clock,
        )
        val cleared = (outcome as PresentmentOutcome.Accepted).authorization
        world.cardAuthorizations[world.cardAuthorizations.lastIndex] = cleared
        world.cardClearings.recordPosted(cleared.id, cleared.clearedAmountMinorUnits)
        world.audit.append("card ${cleared.id} cleared: ${cleared.clearedAmountMinorUnits}")

        // A post-commit conflict forces the compensation branch. The scheme reversal releases
        // the hold only while it still holds funds — a conflict that lands after a FULL
        // presentment finds the authorisation already CLEARED, and the REAL policy refuses to
        // release nothing (the clearing that already posted stays posted, real money left).
        if (world.context.faults.shouldConflict()) {
            when (val reversal = AuthorizationLifecycle.reverse(cleared, world.context.clock)) {
                is PresentmentOutcome.Accepted -> {
                    world.cardAuthorizations[world.cardAuthorizations.lastIndex] = reversal.authorization
                    world.audit.append("card ${cleared.id} reversed: post-commit conflict")
                }
                is PresentmentOutcome.Refused -> {
                    world.audit.append("card ${cleared.id} conflict after terminal clear: nothing to release")
                }
            }
        }
    }

    private fun presentPartiallyThenReverse(world: World, authorized: CardAuthorization) {
        val partial = authorized.amountMinorUnits / 2
        val outcome = AuthorizationLifecycle.clear(
            authorized,
            partial,
            authorized.currencyCode,
            world.context.clock,
        )
        val partiallyCleared = (outcome as PresentmentOutcome.Accepted).authorization
        world.cardAuthorizations[world.cardAuthorizations.lastIndex] = partiallyCleared
        world.cardClearings.recordPosted(partiallyCleared.id, partial)
        world.audit.append("card ${partiallyCleared.id} partially cleared: $partial")

        val reversed = AuthorizationLifecycle.reverse(partiallyCleared, world.context.clock)
        world.cardAuthorizations[world.cardAuthorizations.lastIndex] =
            (reversed as PresentmentOutcome.Accepted).authorization
        world.audit.append("card ${partiallyCleared.id} reversed: remainder released")
    }

    private fun expire(world: World, authorized: CardAuthorization) {
        val outcome = AuthorizationLifecycle.expire(authorized, world.context.clock)
        val expired = (outcome as PresentmentOutcome.Accepted).authorization
        world.cardAuthorizations[world.cardAuthorizations.lastIndex] = expired
        world.audit.append("card ${expired.id} expired: hold released, nothing cleared")
    }

    /** What the acquirer does with the hold in this step. */
    private enum class Action { FULL_CLEAR, PARTIAL_CLEAR_THEN_REVERSE, EXPIRE, LEAVE_HOLDING }
}
