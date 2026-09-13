// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.domain.event

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Token and dispute lifecycle events, on the same topic as the money-path events.
 *
 * ## Why a separate hierarchy from [CardProcessingEvent]
 *
 * That base class requires an `authorizationId`, because every money-path event has one. A token
 * provisioning does not: a wallet credential is minted for a CARD, long before and independently of
 * any authorisation. Extending it would mean inventing a UUID to satisfy a field the event does not
 * have, and every consumer would then have a value it must know to ignore. A second hierarchy is
 * cheaper than a field that lies.
 *
 * ## The two conventions this file must keep
 *
 * `EVENT_TYPE` is a `const val` in each event's own companion, and `sourceService` is a defaulted
 * **constructor parameter** on every subtype rather than one concrete property on the base class.
 * Jackson serialises both shapes identically; only the constructor form is visible to
 * `check-event-contract-code-agreement.py`, which reads the primary constructor to hold the
 * AsyncAPI document against the code. A base-class property makes that gate report a documented
 * field as one the producer never sends — a false finding about a field that IS on the wire.
 *
 * `occurredAt` has no default, for the reason [CardProcessingEvent] states: an `Instant.EPOCH`
 * default is a lie that `isNotNull()` agrees with (#3874, #3882).
 */
sealed class CardLifecycleEvent {
    abstract val cardId: UUID
    abstract val occurredAt: Instant
    abstract val sourceService: String

    companion object {
        const val SOURCE_SERVICE = "card-processing-service"
    }
}

/**
 * A network token was provisioned for a card.
 *
 * [tokenReference] is the network's opaque handle. It is not a card number, cannot be used to
 * derive one, and is the only identifier this platform ever holds for a token.
 */
data class CardTokenProvisioned(
    val registrationId: UUID,
    override val cardId: UUID,
    val tokenReference: String,
    val requestorId: String,
    val requestorLabel: String,
    val scheme: String,
    val status: String,
    val expiry: LocalDate?,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardLifecycleEvent() {
    companion object {
        const val EVENT_TYPE = "card.token.provisioned.v1"
    }
}

/**
 * A token was suspended, resumed or deleted.
 *
 * [previousStatus] travels with it. A consumer that only receives the new state cannot tell a
 * resume from a re-suspension of something already suspended, and "the wallet was turned back on"
 * is precisely the event a customer notification is written for.
 */
data class CardTokenStatusChanged(
    val registrationId: UUID,
    override val cardId: UUID,
    val tokenReference: String,
    val previousStatus: String,
    val status: String,
    val scheme: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardLifecycleEvent() {
    companion object {
        const val EVENT_TYPE = "card.token.status_changed.v1"
    }
}

/**
 * A chargeback case was opened with the network.
 *
 * [respondByDate] is the network's own deadline, carried so a consumer can count down without
 * re-deriving it from a reason code and a scheme rulebook.
 */
data class CardDisputeOpened(
    val disputeId: UUID,
    val authorizationId: UUID,
    override val cardId: UUID,
    val networkCaseId: String,
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val respondByDate: LocalDate?,
    val scheme: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardLifecycleEvent() {
    companion object {
        const val EVENT_TYPE = "card.dispute.opened.v1"
    }
}

/** Evidence was filed against an open case. [documentReference] is a handle, never the document. */
data class CardDisputeEvidenceSubmitted(
    val disputeId: UUID,
    val authorizationId: UUID,
    override val cardId: UUID,
    val networkCaseId: String,
    val documentReference: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardLifecycleEvent() {
    companion object {
        const val EVENT_TYPE = "card.dispute.evidence_submitted.v1"
    }
}

/**
 * The bank-side dispute state moved.
 *
 * Both vocabularies are on the wire: [status] is this bank's lifecycle and [schemeStatus] is the
 * network's own string. A consumer that needs the scheme's word for it must not have to guess it
 * back from ours — see [com.openbank.cardprocessing.domain.model.CardDisputeCase].
 */
data class CardDisputeStatusChanged(
    val disputeId: UUID,
    val authorizationId: UUID,
    override val cardId: UUID,
    val networkCaseId: String,
    val previousStatus: String,
    val status: String,
    val schemeStatus: String,
    override val occurredAt: Instant,
    override val sourceService: String = SOURCE_SERVICE,
) : CardLifecycleEvent() {
    companion object {
        const val EVENT_TYPE = "card.dispute.status_changed.v1"
    }
}
