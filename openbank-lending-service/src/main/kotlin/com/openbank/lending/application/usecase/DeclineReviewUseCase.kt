// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewDecisionRequest
import io.smallrye.mutiny.Uni
import java.util.UUID

/** The review as the caller sees it, and whether this call created it (false on an idempotent repeat). */
data class DeclineReviewResult(val review: DeclineReview, val created: Boolean)

/**
 * Human review of a decision-engine decline (GDPR Art. 22(3), EU AI Act Art. 86). The customer asks; a
 * staff reviewer, never the applicant, confirms the decline or overturns it. Overturning does not
 * resurrect the declined application: it opens a successor that enters the existing four-eyes decision.
 */
interface DeclineReviewUseCase {
    /** Customer leg. [partyId] is the applicant, taken from the edge-vouched header, never from a body. */
    fun requestReview(applicationId: UUID, partyId: UUID): Uni<DeclineReviewResult>

    fun listPending(limit: Int): Uni<List<DeclineReview>>

    /** Staff leg. [reviewer] is the authenticated JWT subject, never client-supplied. */
    fun decide(reviewId: UUID, request: DeclineReviewDecisionRequest, reviewer: String): Uni<DeclineReview>
}
