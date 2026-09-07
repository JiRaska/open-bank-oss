// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.event

import com.openbank.cardprocessing.domain.model.PresentmentChannel
import java.time.Instant
import java.util.UUID

/**
 * What the card money path tells the rest of the platform.
 *
 * These are **serialised data classes**, so every wire key is a Kotlin property name and a grep for
 * a quoted field name finds nothing — the repo has been caught by that asymmetry before (#3883).
 * The type is the contract; `openbank-contracts/openbank-card-processing-service/asyncapi.yaml`
 * documents it, and `check-event-contract-code-agreement.py` holds the two together.
 *
 * [occurredAt] has **no default**. An `Instant.EPOCH` default is a lie every test agrees with:
 * `isNotNull()` passes against 1970 (#3874/#3883). Omitting it is a compile error instead.
 */
sealed class CardProcessingEvent {
    abstract val authorizationId: UUID
    abstract val cardId: UUID
    abstract val occurredAt: Instant

    /**
     * Producing service, read by audit-service as the strongest (EVENT-sourced) attribution
     * (#5256).
     *
     * An abstract property here, repeated as a defaulted constructor parameter on every subtype,
     * rather than one concrete `val` on this base class. Jackson serialises both shapes
     * identically, but only the constructor form is visible to
     * `check-event-contract-code-agreement.py`, which reads the data class to hold the AsyncAPI
     * document against the code. A base-class property makes the gate report the documented field
     * as one the producer never sends — a false finding about a field that IS on the wire.
     */
    abstract val sourceService: String

    companion object {
        const val SOURCE_SERVICE = "card-processing-service"
    }
}

data class CardAuthorised(
    override val authorizationId: UUID,
    override val cardId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String?,
    val category: String,
    val merchantName: String?,
    val merchantCountry: String?,
    val expiresAt: Instant,
    /**
     * The agent that initiated this purchase under an AP2 mandate, or null for a human one
     * (ADR-0283 D6). Additive and nullable: every existing consumer keeps parsing this event
     * unchanged, and one that cares can now tell agentic spend from a card tap.
     */
    val initiatedByAgentId: String? = null,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardProcessingEvent() {
    companion object {
        /**
         * The wire event type. Declared HERE, on the event, rather than on the service that emits
         * it: `check-event-contract-code-agreement.py` reads this constant to hold the AsyncAPI
         * contract against the code, and a contract describing an event nobody emits (or an event
         * nobody declared) is exactly the drift ADR-0006 exists to stop.
         */
        const val EVENT_TYPE = "card.authorised.v1"
    }
}

/**
 * A decline is published, not only returned.
 *
 * [reason] is card-issuance's `DeclineReason` name, carried verbatim rather than re-worded: the
 * customer-facing explanation and the enforcement point must not be able to disagree, and a
 * re-mapping here is exactly where they would.
 */
data class CardDeclined(
    override val authorizationId: UUID,
    override val cardId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val reason: String,
    val category: String,
    /** See [CardAuthorised.initiatedByAgentId]. A decline of agentic spend is the more interesting one. */
    val initiatedByAgentId: String? = null,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardProcessingEvent() {
    companion object {
        /** See [CardAuthorised.EVENT_TYPE] for why the constant lives on the event. */
        const val EVENT_TYPE = "card.declined.v1"
    }
}

data class CardCleared(
    override val authorizationId: UUID,
    override val cardId: UUID,
    val accountId: UUID,
    val clearedAmountMinorUnits: Long,
    val cumulativeClearedMinorUnits: Long,
    val currencyCode: String,
    val fullyCleared: Boolean,
    val category: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardProcessingEvent() {
    companion object {
        /** See [CardAuthorised.EVENT_TYPE] for why the constant lives on the event. */
        const val EVENT_TYPE = "card.cleared.v1"
    }
}

/**
 * The hold was released without the money moving — a scheme reversal, or an expiry the bank ran
 * itself. [releasedAmountMinorUnits] is what the customer got back, which is the number a client
 * shows; the status alone does not say how much.
 */
data class CardHoldReleased(
    override val authorizationId: UUID,
    override val cardId: UUID,
    val accountId: UUID,
    val releasedAmountMinorUnits: Long,
    val currencyCode: String,
    /** `REVERSAL` or `EXPIRY` — the two are operationally different and must not share a value. */
    val releaseKind: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardProcessingEvent() {
    companion object {
        /** See [CardAuthorised.EVENT_TYPE] for why the constant lives on the event. */
        const val EVENT_TYPE = "card.hold_released.v1"
    }
}
