// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

import java.time.Instant
import java.util.UUID

/**
 * One party's passage through a campaign (ADR-0200 D1). The Temporal workflow id is
 * `campaign-journey-{campaignId}-{partyId}`, which doubles as the idempotency key that makes
 * double-enrolment impossible.
 */
data class Enrolment(
    val id: UUID,
    val campaignId: UUID,
    val partyId: UUID,
    val state: EnrolmentState,
    val currentStep: Int,
    val startedAt: Instant,
    val completedAt: Instant?,
)

enum class EnrolmentState {
    ACTIVE,
    COMPLETED,

    /** Journey terminated mid-flight by a consent.revoked signal (ADR-0200 D2 push mechanism). */
    TERMINATED_CONSENT_REVOKED,

    /** Journey ended early because a step's suppression check failed (ADR-0200 D6). */
    TERMINATED_SUPPRESSED,
}

/** A recorded send decision — the input to the frequency-cap evaluation (ADR-0219 D2). */
data class SendRecord(
    val id: UUID,
    val campaignId: UUID,
    val partyId: UUID,
    val stepOrder: Int,
    val outcome: SendOutcome,
    val occurredAt: Instant,
    /**
     * What became of the message, as reported back by notification-service (ADR-0239 D3).
     *
     * [outcome] and this are two different facts and must not be collapsed. `SENT` says
     * "notification-service accepted the request" — a handoff acknowledgement this service can
     * observe for itself. It says nothing about delivery, and the two routinely diverge: the
     * marketing consent gate suppresses, the party has no resolvable address, the mailer refuses.
     * Every one of those used to show as `SENT` here with nothing to contradict it (issue #3663).
     *
     * `PENDING` is the honest resting state — no outcome has arrived — and is NOT evidence of a
     * problem on its own: an outcome for a send made seconds ago has not had time to land, and a
     * send made by a producer that set no correlation id can never be updated at all.
     */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
    /** The reported cause, verbatim from the outcome event. Free-form and open-ended by contract. */
    val deliveryReason: String? = null,
    /** When [deliveryStatus] last moved. Null while it has never moved off `PENDING`. */
    val deliveryUpdatedAt: Instant? = null,
)

enum class SendOutcome { SENT, SUPPRESSED_CAP, SUPPRESSED_QUIET_HOURS, SUPPRESSED_CONSENT, SUPPRESSED_LIST, FAILED }

/**
 * The confirmed fate of a send, distinct from the handoff [SendOutcome] (ADR-0239 D3).
 *
 * Deliberately a NEW field rather than more `SendOutcome` constants or a rename of `SENT`:
 * renaming would break the admin-ui funnel and the campaign OpenAPI contract to buy a label, and
 * folding delivery into `outcome` would destroy the only record of what this service actually
 * decided — the frequency cap counts `SENT` rows, and a cap must not change meaning because a
 * mailer bounced.
 */
enum class DeliveryStatus { PENDING, CONFIRMED, SUPPRESSED, FAILED }

/**
 * The monotonic transition rule for [DeliveryStatus] (ADR-0239 D4).
 *
 * A pure function, and a separate one, because it is the whole correctness of the consumer and the
 * consumer itself cannot be unit-tested without a broker. Delivery is at-least-once and the topic
 * is partitioned by `notificationId`, NOT by correlation id — so two outcomes for one send arrive
 * in no guaranteed order. Last-write-wins would therefore let a stale duplicate overwrite a fresh
 * terminal state; first-terminal-wins does not care about order at all.
 */
object DeliveryTransition {

    /** The outcome vocabulary of `openbank.notification.outcomes.v1`. */
    const val OUTCOME_SENT = "SENT"
    const val OUTCOME_SUPPRESSED = "SUPPRESSED"
    const val OUTCOME_FAILED = "FAILED"
    const val OUTCOME_BOUNCED = "BOUNCED"

    /**
     * The state [current] should move to on [outcome], or null to leave it untouched.
     *
     * Null — not "the same value" — so a caller can skip the write entirely and a test can tell
     * "ignored" from "re-applied". An [outcome] this version does not recognise is ignored: the
     * contract is additive and a consumer must never treat an unknown value as an error.
     */
    fun next(current: DeliveryStatus, outcome: String): DeliveryStatus? = when (outcome) {
        OUTCOME_SENT -> DeliveryStatus.CONFIRMED.takeIf { current == DeliveryStatus.PENDING }
        OUTCOME_SUPPRESSED -> DeliveryStatus.SUPPRESSED.takeIf { current == DeliveryStatus.PENDING }
        OUTCOME_FAILED -> DeliveryStatus.FAILED.takeIf { current == DeliveryStatus.PENDING }
        // The one genuine later refinement: SMTP accepted the message, then it bounced. This is the
        // only transition out of a terminal state, and it exists because CONFIRMED was true when it
        // was written and stopped being true afterwards.
        OUTCOME_BOUNCED -> DeliveryStatus.FAILED.takeIf {
            current == DeliveryStatus.PENDING || current == DeliveryStatus.CONFIRMED
        }
        else -> null
    }
}
