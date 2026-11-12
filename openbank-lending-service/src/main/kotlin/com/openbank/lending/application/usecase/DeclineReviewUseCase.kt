// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.domain.model.DeclineReview
import io.smallrye.mutiny.Uni
import java.util.UUID

/** The review as the caller sees it, and whether this call created it (false on an idempotent repeat). */
data class DeclineReviewResult(val review: DeclineReview, val created: Boolean)

/**
 * Human review of a decision-engine decline (GDPR Art. 22(3), EU AI Act Art. 86). The customer asks;
 * the staff decision (confirm, or overturn into a successor application) follows in a separate change.
 */
interface DeclineReviewUseCase {
    /** Customer leg. [partyId] is the applicant, taken from the edge-vouched header, never from a body. */
    fun requestReview(applicationId: UUID, partyId: UUID): Uni<DeclineReviewResult>
}
