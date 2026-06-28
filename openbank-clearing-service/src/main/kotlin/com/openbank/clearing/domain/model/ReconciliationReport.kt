// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Result of an internal consistency check on a settled clearing batch.
 *
 * A batch is "clean" when [settledItemCount] equals [expectedItemCount] (i.e. all items
 * transitioned from IN_CLEARING to SETTLED). Any items still in IN_CLEARING or FAILED
 * appear in [stuckItemIds] for operator review.
 */
data class ReconciliationReport(
    val batchId: UUID,
    val cycleId: String?,
    val expectedItemCount: Int,
    val settledItemCount: Int,
    val stuckItemIds: List<UUID>,
    val checkedAt: OffsetDateTime,
) {
    val clean: Boolean get() = stuckItemIds.isEmpty()
}
