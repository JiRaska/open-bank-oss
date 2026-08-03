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
)

enum class SendOutcome { SENT, SUPPRESSED_CAP, SUPPRESSED_QUIET_HOURS, SUPPRESSED_CONSENT, SUPPRESSED_LIST, FAILED }
