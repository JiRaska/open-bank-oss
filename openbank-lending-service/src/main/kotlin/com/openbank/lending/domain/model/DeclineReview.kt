// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/** Where a customer-requested review of an engine decline stands. Its own enum: no boolean carries it. */
enum class DeclineReviewStatus { REQUESTED, CONFIRMED, OVERTURNED }

/**
 * A customer's request for human review of a decision-engine decline (GDPR Art. 22(3), EU AI Act
 * Art. 86, EBA/GL/2020/06 par. 54). One per application. The engine's outcome, reason codes and policy
 * versions are snapshotted at request time so the reviewer sees what the customer was told, and is not
 * bound by it. [successorApplicationId] is set only when the review was OVERTURNED: the declined
 * application itself never leaves its terminal state.
 */
data class DeclineReview(
    val id: UUID,
    val applicationId: UUID,
    val partyId: UUID,
    val requestedAt: OffsetDateTime,
    val engineOutcome: String,
    val engineReasons: String?,
    val policyVersions: String?,
    val engineDecidedAt: OffsetDateTime,
    val status: DeclineReviewStatus = DeclineReviewStatus.REQUESTED,
    val reviewedBy: String? = null,
    val reviewedAt: OffsetDateTime? = null,
    val reviewReason: String? = null,
    val successorApplicationId: UUID? = null,
)

/** The reviewer's decision. [reason] is mandatory (see [MIN_REASON_LENGTH]) and never leaves the service in events. */
data class DeclineReviewDecisionRequest(val outcome: DeclineReviewStatus, val reason: String) {
    init {
        require(outcome != DeclineReviewStatus.REQUESTED) { "outcome must be CONFIRMED or OVERTURNED" }
        require(reason.trim().length >= MIN_REASON_LENGTH) {
            "review reason must be at least $MIN_REASON_LENGTH characters"
        }
        require(reason.length <= MAX_REASON_LENGTH) { "review reason must be at most $MAX_REASON_LENGTH characters" }
    }

    companion object {
        const val MIN_REASON_LENGTH = 10
        const val MAX_REASON_LENGTH = 512
    }
}
