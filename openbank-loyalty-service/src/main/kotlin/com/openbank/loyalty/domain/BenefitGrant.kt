// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import java.time.Instant
import java.util.UUID

/**
 * How far a redemption has actually got. Four values, and the split between the first two is the
 * point of the type.
 *
 * [RESERVED] means the Lístky are held and nothing has been delivered. [GRANTED] means this
 * service has durably recorded that the benefit is owed and published it for the delivering
 * engine. **Neither means the customer has received anything** — that is the engine's to report,
 * and until ADR-0282 phase 2b wires the engines there is no value here that claims it. Reading
 * "granted" as "applied" is the same mistake as reading an APNs 200 as a delivery receipt, which
 * this platform shipped once and a customer found.
 */
enum class BenefitGrantStatus { RESERVED, GRANTED, RELEASED, REVERSED }

/**
 * A redemption of Lístky for a catalogue [Benefit].
 *
 * [idempotencyKey] is unique per grant at the database level, so a retried request resolves to the
 * same grant instead of burning twice — the reserve-then-commit semantics ADR-0266 already gives
 * promo codes, and the reason a network retry cannot cost a party their balance.
 */
data class BenefitGrant(
    val id: UUID,
    val partyId: UUID,
    val benefitId: String,
    val price: Leaves,
    val status: BenefitGrantStatus,
    val idempotencyKey: String,
    val reservedAt: Instant,
    val grantedAt: Instant? = null,
    val expiresAt: Instant? = null,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "an idempotency key is required to reserve a benefit" }
        require(status != BenefitGrantStatus.GRANTED || grantedAt != null) {
            "a GRANTED benefit must carry the instant it was granted"
        }
    }
}

/**
 * The outcome of asking to redeem. A sealed hierarchy rather than a nullable grant, so "the party
 * could not afford it" and "the benefit does not exist" cannot collapse into one another, and
 * neither can pass for success.
 */
sealed class RedemptionOutcome {
    /** The benefit is owed and the Lístky are burned. */
    data class Granted(val grant: BenefitGrant) : RedemptionOutcome()

    /** A previous request with the same idempotency key already did this. */
    data class AlreadyGranted(val grant: BenefitGrant) : RedemptionOutcome()

    /** [available] is what the party held at the time of the attempt. */
    data class InsufficientLeaves(val required: Leaves, val available: Leaves) : RedemptionOutcome()

    data class UnknownBenefit(val benefitId: String) : RedemptionOutcome()
}

/**
 * The outcome of an earn attempt. [Capped] is its own value and never shares a signal with
 * [Awarded] — ADR-0282 D5, and the `PushResult.skipped()` lesson it is written from.
 */
sealed class EarnOutcome {
    data class Awarded(val entry: LeafLedgerEntry) : EarnOutcome()

    /** The annual cap refused the award. [remaining] is the headroom that was left. */
    data class Capped(val requested: Leaves, val remaining: Leaves) : EarnOutcome()

    /** This exact achievement has already been awarded — a replay, not a second achievement. */
    data class AlreadyAwarded(val entry: LeafLedgerEntry) : EarnOutcome()
}
