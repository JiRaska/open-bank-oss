// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.model

import java.time.Instant
import java.util.UUID

/**
 * How the card was presented, as the acquirer states it.
 *
 * A **separate type** from card-issuance's `AuthorizationChannel` even though the four values
 * match today: this one crosses a service boundary as a wire value, so a rename there must not
 * silently change what an acquirer may send here. The mapping is one explicit place
 * ([com.openbank.cardprocessing.infrastructure.client.CardIssuanceAdapter]).
 */
enum class PresentmentChannel { CONTACTLESS, ONLINE, ATM, CHIP_AND_PIN }

/**
 * The life of one authorisation.
 *
 * **`DECLINED` is a terminal state that holds nothing** — it is recorded, not merely returned,
 * because a decline the customer disputes ("my card was refused at the till") is unanswerable from
 * a log line that has aged out, and because the decline rate per reason is the only signal that
 * separates a control that is misconfigured from one that is merely strict.
 */
enum class AuthorizationStatus {
    APPROVED,
    DECLINED,
    PARTIALLY_CLEARED,
    CLEARED,
    REVERSED,
    EXPIRED,
    ;

    /** True while the authorisation still holds funds the customer cannot spend twice. */
    val holdsFunds: Boolean get() = this == APPROVED || this == PARTIALLY_CLEARED

    val isTerminal: Boolean get() = this == DECLINED || this == CLEARED || this == REVERSED || this == EXPIRED
}

/**
 * One card authorisation, from the acquirer's request to its clearing or release.
 *
 * Amounts are **minor units** (`Long`), never `BigDecimal`: a card amount is an integral number of
 * the currency's smallest unit at every point in the scheme message, and a decimal type invites a
 * rounding decision that has no correct answer here. The ledger posting converts once, at the
 * boundary, where the ledger's own scale applies.
 *
 * No PAN, no card credential, nothing from the vault: the card is referenced by its card-issuance
 * id. That is what keeps this service out of the cardholder-data environment (ADR-0283 D7).
 */
data class CardAuthorization(
    val id: UUID,
    val cardId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String?,
    val merchantName: String?,
    val merchantCountry: String?,
    val status: AuthorizationStatus,
    /** The category card-issuance judged the MCC as; kept so a later clearing does not re-judge it. */
    val category: String,
    /** Populated exactly when [status] is [AuthorizationStatus.DECLINED]. */
    val declineReason: String?,
    val clearedAmountMinorUnits: Long,
    /** The acquirer's own reference, echoed back so a reversal can be matched without our id. */
    val networkReference: String?,
    /**
     * The agent that initiated this purchase under an AP2 mandate, or null for a human one
     * (ADR-0283 D6).
     *
     * On the AGGREGATE rather than derived at read time, because a dispute about agentic spend turns
     * on whether an agent was acting and which one — a fact about the moment of authorisation that
     * no later lookup can reconstruct once the mandate has expired.
     */
    val initiatedByAgentId: String? = null,
    val authorizedAt: Instant,
    /** When an uncleared hold is released. A hold that never expires is a permanent freeze. */
    val expiresAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Funds still held: the authorised amount less what has already cleared, and zero once the
     * authorisation stops holding anything.
     *
     * Deliberately derived rather than stored. A stored hold balance is a second source of truth
     * that drifts from the cleared total, and the drift is invisible — both numbers look plausible.
     */
    val heldAmountMinorUnits: Long
        get() = if (status.holdsFunds) (amountMinorUnits - clearedAmountMinorUnits).coerceAtLeast(0) else 0

    /**
     * What this authorisation counts as SPENT for limit purposes.
     *
     * A hold counts in full even before it clears — that is the point of a hold, and counting only
     * cleared amounts would let a customer exceed a daily limit by the total of everything still
     * in flight. A released authorisation (reversed, expired, declined) counts as nothing.
     */
    val effectiveSpendMinorUnits: Long
        get() = when (status) {
            AuthorizationStatus.APPROVED, AuthorizationStatus.PARTIALLY_CLEARED -> amountMinorUnits
            AuthorizationStatus.CLEARED -> clearedAmountMinorUnits
            AuthorizationStatus.DECLINED, AuthorizationStatus.REVERSED, AuthorizationStatus.EXPIRED -> 0
        }
}

/** Why a clearing, reversal or expiry was refused. Each value is something an operator can act on. */
enum class PresentmentRefusal {
    /** The authorisation is terminal — cleared, reversed, expired or declined. */
    NOT_HOLDING_FUNDS,

    /** The presented amount is zero or negative. */
    AMOUNT_NOT_POSITIVE,

    /** Cumulative clearing would exceed the authorised amount. */
    EXCEEDS_AUTHORIZED_AMOUNT,

    /** The presentment is in a different currency than the authorisation. */
    CURRENCY_MISMATCH,

    /** Expiry was attempted before the hold's own expiry instant. */
    NOT_YET_EXPIRED,
}

/** A refusal carries the reason; a success carries the new state. Never an exception: see the policy. */
sealed interface PresentmentOutcome {
    data class Accepted(val authorization: CardAuthorization) : PresentmentOutcome

    data class Refused(val reason: PresentmentRefusal) : PresentmentOutcome
}
