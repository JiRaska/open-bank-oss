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

    /**
     * Journey stopped by the campaign's own stop condition (ADR-0200 D1, #3585): the party's
     * lifetime send count in this campaign reached the definition's cap. Distinct from
     * [TERMINATED_SUPPRESSED] — suppression is a per-step policy outcome, this is the definition
     * choosing to end the journey, so the console can say why the funnel ended early.
     */
    STOPPED_MAX_SENDS,
}

/** A recorded send decision — the input to the frequency-cap evaluation (ADR-0219 D2). */
data class SendRecord(
    val id: UUID,
    val campaignId: UUID,
    val partyId: UUID,
    val stepOrder: Int,
    val outcome: SendOutcome,
    val occurredAt: Instant,
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
    DRY_RUN,
    SUPPRESSED_CAP,
    SUPPRESSED_QUIET_HOURS,
    SUPPRESSED_CONSENT,
    SUPPRESSED_LIST,
    FAILED,
}
