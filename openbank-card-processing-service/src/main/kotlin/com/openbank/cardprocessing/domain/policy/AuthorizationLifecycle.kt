// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.policy

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.domain.model.PresentmentRefusal
import java.time.Clock
import java.time.Instant

/**
 * Every transition an authorisation can make after it exists.
 *
 * Pure by construction: no repository, no wall clock, no I/O — the clock arrives as an argument.
 * That is not style. A money-path transition that can only be exercised against a live acquirer is
 * a transition nobody checks, and this repo has shipped exactly that shape before: card-issuance's
 * own channel controls were stored and consulted by nothing until ADR-0194 D3.
 *
 * **Refusals are values, not exceptions.** A clearing that arrives twice, or after a reversal, is
 * an ordinary event in scheme traffic — not a programming error — and a caller that retries a
 * thrown exception gets the same throw. Each refusal names something an operator can act on.
 */
object AuthorizationLifecycle {

    /**
     * Applies one presentment (a clearing record) against an authorisation.
     *
     * Over-clearing is REFUSED rather than tolerated. Some schemes permit a small overage on
     * specific merchant categories (fuel, hospitality gratuity); implementing that needs the
     * per-category tolerance table from the scheme rules, and guessing a percentage would let real
     * money past a control while looking deliberate. Until the table exists, an overage is an
     * exception an operator sees.
     */
    fun clear(
        authorization: CardAuthorization,
        amountMinorUnits: Long,
        currencyCode: String,
        clock: Clock,
    ): PresentmentOutcome {
        if (!authorization.status.holdsFunds) return PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS)
        if (amountMinorUnits <= 0) return PresentmentOutcome.Refused(PresentmentRefusal.AMOUNT_NOT_POSITIVE)
        if (!currencyCode.equals(authorization.currencyCode, ignoreCase = true)) {
            return PresentmentOutcome.Refused(PresentmentRefusal.CURRENCY_MISMATCH)
        }
        val cumulative = authorization.clearedAmountMinorUnits + amountMinorUnits
        if (cumulative > authorization.amountMinorUnits) {
            return PresentmentOutcome.Refused(PresentmentRefusal.EXCEEDS_AUTHORIZED_AMOUNT)
        }
        val fullyCleared = cumulative == authorization.amountMinorUnits
        return PresentmentOutcome.Accepted(
            authorization.copy(
                status = if (fullyCleared) AuthorizationStatus.CLEARED else AuthorizationStatus.PARTIALLY_CLEARED,
                clearedAmountMinorUnits = cumulative,
                updatedAt = Instant.now(clock),
            ),
        )
    }

    /**
     * Releases the remaining hold.
     *
     * A partially cleared authorisation may still be reversed: the scheme's reversal releases what
     * has not been presented, and what already cleared stays cleared. The cleared total is
     * therefore left untouched — zeroing it here would silently un-post money the ledger has.
     */
    fun reverse(authorization: CardAuthorization, clock: Clock): PresentmentOutcome {
        if (!authorization.status.holdsFunds) return PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS)
        return PresentmentOutcome.Accepted(
            authorization.copy(status = AuthorizationStatus.REVERSED, updatedAt = Instant.now(clock)),
        )
    }

    /**
     * Releases a hold the acquirer never presented against.
     *
     * Refused before [CardAuthorization.expiresAt] — an early expiry would release funds an
     * acquirer is still entitled to present against, and the caller (a scheduler) is exactly the
     * place where an off-by-one in a window calculation lands silently.
     */
    fun expire(authorization: CardAuthorization, clock: Clock): PresentmentOutcome {
        if (!authorization.status.holdsFunds) return PresentmentOutcome.Refused(PresentmentRefusal.NOT_HOLDING_FUNDS)
        val now = Instant.now(clock)
        if (now.isBefore(authorization.expiresAt)) return PresentmentOutcome.Refused(PresentmentRefusal.NOT_YET_EXPIRED)
        return PresentmentOutcome.Accepted(authorization.copy(status = AuthorizationStatus.EXPIRED, updatedAt = now))
    }
}
