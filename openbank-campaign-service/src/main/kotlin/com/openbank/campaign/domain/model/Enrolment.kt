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
    /** Stored experiment assignment; historical rows default to [ExperimentCohort.TREATMENT]. */
    val experimentCohort: ExperimentCohort = ExperimentCohort.TREATMENT,
    /**
     * Stable content arm, null when this campaign has no content experiment or this party is a
     * no-contact holdout. Persisted rather than recalculated so an audit remains true if the
     * allocation implementation ever changes.
     */
    val contentVariant: ContentVariant? = null,
    /** Durable, per-party evidence of each explicit delivery decision the journey evaluated. */
    val decisionPath: List<DecisionPathSelection> = emptyList(),
)

/** One selected edge in a reviewed campaign graph. */
data class DecisionPathSelection(
    val sourceStepOrder: Int,
    val selected: DecisionPath,
    val nextStepOrder: Int,
    val decidedAt: Instant,
    /**
     * The raw [DeliveryStatus] the decision was evaluated from, snapshotted at write time
     * (ADR-0263 Phase A). [selected] is already derivable from this plus the predicate, but
     * without it a reviewer reconstructing "why did this path fire" has to join back to the
     * send-log row by `(campaignId, partyId, sourceStepOrder)` — sound only because
     * `DeliveryStatus` transitions are monotonic (ADR-0239 D4), and one avoidable hop regardless.
     * Null for every selection recorded before this field existed; that default is load-bearing,
     * not a placeholder — see [CampaignStep.condition] for the identical precedent.
     */
    val observedStatus: DeliveryStatus? = null,
)

enum class DecisionPath { CONFIRMED, NOT_CONFIRMED }

enum class EnrolmentState {
    ACTIVE,
    COMPLETED,

    /** Journey terminated mid-flight by a consent.revoked signal (ADR-0200 D2 push mechanism). */
    TERMINATED_CONSENT_REVOKED,

    /** Campaign was closed while this party was still in its journey. */
    TERMINATED_CAMPAIGN_CLOSED,

    /** The campaign's observed product goal was reached, so no further persuasion is lawful or useful. */
    COMPLETED_GOAL_REACHED,

    /** Journey ended early because a step's suppression check failed (ADR-0200 D6). */
    TERMINATED_SUPPRESSED,

    /**
     * Journey stopped by the campaign's own stop condition (ADR-0200 D1, #3585): the party's
     * lifetime send count in this campaign reached the definition's cap. Distinct from
     * [TERMINATED_SUPPRESSED] — suppression is a per-step policy outcome, this is the definition
     * choosing to end the journey, so the console can say why the funnel ended early.
     */
    STOPPED_MAX_SENDS,

    /** Intentionally not contacted, retained to measure the campaign against a control cohort. */
    HOLDOUT,
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
    /** The actual medium passed to notification-service; null for historical and non-send rows. */
    val channel: Channel? = null,
)

/**
 * What happened to one step for one party.
 *
 * `DRY_RUN` is a distinct outcome, not a flavour of SENT. A non-production environment must be able
 * to exercise a whole journey — enrolment, cap, quiet hours, consent, ordering, delays — without a
 * single message leaving the platform, and the send log has to say which of those two it was.
 * Folding it into SENT would make the console report deliveries that never happened, and the first
 * person to compare it with an inbox would be right and the screen wrong.
 */
enum class SendOutcome {
    SENT,

    /**
     * The party did the thing the campaign existed to cause (ADR-0245). Recorded by
     * `ConversionConsumer` from a product event, never by the sending path — it is an OUTCOME, not
     * a delivery, and the console must not add it to a send count.
     */
    CONVERTED,
    DRY_RUN,
    SUPPRESSED_CAP,
    SUPPRESSED_QUIET_HOURS,
    SUPPRESSED_CONSENT,
    SUPPRESSED_LIST,

    /**
     * ADR-0269 rule 2 refused this step: the party may hear about credit and must not hear it now.
     * A recorded row, not silence — the send log is where an operator reconstructs why a journey
     * went quiet, and "the distress floor stopped it" is the answer they need. The column is TEXT
     * with no CHECK constraint, so this needs no migration.
     */
    SUPPRESSED_CREDIT_DISTRESS,

    /**
     * The step's ADR-0200 D1 branch condition did not hold, so nothing was attempted (#3585).
     *
     * A recorded row rather than silence: a skipped step that leaves no trace makes the console's
     * funnel understate the journey and gives an operator no way to tell "this branch was not
     * taken" from "this step never ran". It is NOT a suppression — no policy denied anything, and
     * it must not be read as one — and it does not consume the frequency cap, which counts `SENT`.
     */
    SKIPPED_CONDITION,
    FAILED,
}

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
