// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.domain.payment

/**
 * Whether a booking's money actually crosses the bank's boundary — the single question that decides
 * if a clearing calendar may touch its value date.
 *
 * A cutoff time and a business-day roll describe a *scheme's* behaviour: CERTIS closes at 16:00,
 * SWIFT does not run on Sunday. Applied to money that never reaches a scheme, they are pure
 * invention, and an expensive one — a future value date makes the credit leg
 * `notYetEffectiveCredit`, which suppresses `effectiveAvailableAmount`, which makes the next hold
 * fail. That is how an own-account transfer came to be silently refused for weeks (#4869), and the
 * same rule was still being applied to in-house domestic payments, welcome bonuses and reversals.
 *
 * **The discriminator is the payee leg, not the rail.** `targetAccountId` is set only when there is
 * an account of ours to credit — domestic-payment resolves it for in-house transfers and leaves it
 * null when "the money genuinely leaves the bank" (its own words), and sepa-payment, swift-service
 * and sepa-instant never set it at all. Rail is deliberately *not* trusted here: it arrives as a
 * free-form string parsed with `PaymentRail.valueOf(...)` inside a swallowing `runCatching`, so an
 * unparseable value lands as null and is from then on indistinguishable from "no rail supplied".
 * Every sender today does send a real member (sepa-payment `SEPA_CT`, sepa-instant `SEPA_INST`,
 * each from its own `SettlementAdapter`), so that is latent rather than live — but a guard keyed
 * on `rail == null` would hand same-day booking to genuinely external SEPA payments the day one
 * stops. `SCT_INST`, `SEPA` and `FX` do occur in those services and are *not* members: they are
 * fraud-scoring labels on `FraudScoreCommand.rail`, a deliberately free-form field that
 * fraud-service stores as `String` and never parses. #8699 carries the trace.
 */
object SettlementScope {

    /**
     * Rails that are internal by definition, whatever else is on the booking. Listed so that
     * stamping a rail honestly can never re-introduce the bug: before this, the only guard was
     * `rail == null`, so the day someone set `rail = INTERNAL` on an own-account transfer — the
     * correct thing to do under ADR-0103 — it would have started rolling again.
     */
    private val ALWAYS_IN_HOUSE = setOf(PaymentRail.INTERNAL, PaymentRail.FEE, PaymentRail.INTEREST)

    /**
     * True when this booking credits an account of ours, or rides a rail that never leaves the
     * bank. Such a booking settles the moment it is made: there is no scheme to wait for, so there
     * is no calendar to roll against.
     *
     * [hasInternalPayee] is `targetAccountId != null` at the call site.
     */
    fun staysInTheBank(rail: PaymentRail?, hasInternalPayee: Boolean): Boolean =
        hasInternalPayee || (rail != null && rail in ALWAYS_IN_HOUSE)
}
