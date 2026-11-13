// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewStatus
import io.smallrye.mutiny.Uni
import java.time.OffsetDateTime
import java.util.UUID

/** Persistence of customer-requested decline reviews. At most one row per application (unique in the schema). */
interface DeclineReviewRepository {
    /** Insert a new REQUESTED review. Fails with a constraint violation if the application already has one. */
    fun insert(review: DeclineReview): Uni<DeclineReview>

    fun loadById(id: UUID): Uni<DeclineReview?>
    fun loadByApplication(applicationId: UUID): Uni<DeclineReview?>

    /** Oldest first, REQUESTED only. */
    fun listPending(limit: Int): Uni<List<DeclineReview>>

    /**
     * Decide a review **only if** the stored row is still REQUESTED, as one statement. Returns rows
     * claimed: `1` when this caller decided it, `0` when someone else already had. The caller must
     * treat `0` as a refusal and perform none of the decision's side effects.
     */
    fun claimDecision(
        id: UUID,
        to: DeclineReviewStatus,
        reviewedBy: String,
        reviewedAt: OffsetDateTime,
        reason: String,
        successorApplicationId: UUID?,
    ): Uni<Int>
}
