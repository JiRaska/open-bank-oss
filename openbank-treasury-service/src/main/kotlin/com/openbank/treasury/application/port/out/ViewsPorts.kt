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

/**
 * What a close request is, for idempotency: sha256 over date, book, scenario, special (the endpoint)
 * and the recording actor. Derived from the persisted columns too, so closes recorded before this
 * binding existed are checked the same way and need no backfill.
 */
fun PositionClose.requestFingerprint(): String {
    val canonical = listOf(date.toString(), bookId.orEmpty(), scenario.orEmpty(), special.toString(), closedBy)
        .joinToString("\u0000")
    return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class SavedFilter(val owner: String, val name: String, val filterJson: String, val updatedAt: Instant)

interface ViewsRepository {
    /**
     * Idempotent on [commandKey], bound to the request: a replay of the same request (same
     * [PositionClose.requestFingerprint]) returns the close it recorded; the key reused for a different
     * date, book, scenario, endpoint or actor is an [IllegalStateException] (409), never a silent
     * answer with the old close. A lost insert race re-reads and is judged the same way.
     */
    suspend fun recordClose(close: PositionClose, commandKey: String): PositionClose

    suspend fun filters(owner: String): List<SavedFilter>

    suspend fun saveFilter(filter: SavedFilter): SavedFilter

    /** False when the owner has no such filter. */
    suspend fun deleteFilter(owner: String, name: String): Boolean
}
