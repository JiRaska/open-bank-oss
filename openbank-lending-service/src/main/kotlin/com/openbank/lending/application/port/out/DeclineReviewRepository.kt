// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.DeclineReview
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Persistence of customer-requested decline reviews. At most one row per application (unique in the schema). */
interface DeclineReviewRepository {
    /** Insert a new REQUESTED review. Fails with a constraint violation if the application already has one. */
    fun insert(review: DeclineReview): Uni<DeclineReview>

    fun loadByApplication(applicationId: UUID): Uni<DeclineReview?>
}
