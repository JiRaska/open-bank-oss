// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A recorded position close (ADR-0344 catalogue: close-of-day and special closes). */
data class PositionClose(
    val snapshotId: UUID,
    val date: LocalDate,
    val bookId: String?,
    val scenario: String?,
    val special: Boolean,
    val closedBy: String,
    val closedAt: Instant,
    val positionsJson: String,
)

data class SavedFilter(val owner: String, val name: String, val filterJson: String, val updatedAt: Instant)

interface ViewsRepository {
    /** Idempotent on [commandKey]: a replay returns the close it recorded. */
    suspend fun recordClose(close: PositionClose, commandKey: String): PositionClose

    suspend fun filters(owner: String): List<SavedFilter>

    suspend fun saveFilter(filter: SavedFilter): SavedFilter

    /** False when the owner has no such filter. */
    suspend fun deleteFilter(owner: String, name: String): Boolean
}
