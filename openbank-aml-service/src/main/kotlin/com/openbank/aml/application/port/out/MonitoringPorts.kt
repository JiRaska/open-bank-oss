// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.monitoring.BookedMovement
import com.openbank.aml.domain.monitoring.MovementDirection
import com.openbank.aml.domain.monitoring.UncountedMovement
import java.time.Instant
import java.util.UUID

/** Durable intake state of post-booking transaction monitoring (ADR-0371 slice 1). */
interface MovementIntakeRepository {
    /** Records a booked movement once; `false` when the same booking was already recorded. */
    suspend fun recordMovement(movement: BookedMovement, recordedAt: Instant): Boolean

    /** Records the rail of an originating transaction once; first write wins. */
    suspend fun recordOrigin(transactionId: UUID, rail: String, recordedAt: Instant): Boolean

    /** Movements not counted yet, least recently attempted first. */
    suspend fun findUncounted(limit: Int): List<UncountedMovement>

    suspend fun rememberParty(accountId: UUID, partyId: UUID, resolvedAt: Instant)

    /** Marks an attribution attempt that could not finish, so the row yields its place in the queue. */
    suspend fun markAttempted(movement: UncountedMovement, attemptedAt: Instant)

    /**
     * Attributes the movement and increments its counters in ONE transaction. `false` when another
     * tick already counted it; the counters are then untouched.
     */
    suspend fun count(attribution: MovementAttribution): Boolean

    suspend fun backlog(): AttributionBacklog
}

data class MovementAttribution(
    val movement: UncountedMovement,
    val partyId: UUID,
    val rail: String,
    val schemeRevision: Int,
    val bucketSeconds: Long,
    val bucketStart: Instant,
    val bandCodes: List<String>,
    val countedAt: Instant,
)

data class AttributionBacklog(val uncounted: Long, val oldestRecordedAt: Instant?)

/** account -> owning party. `null` means "not known right now", never "has no party". */
fun interface AccountPartyPort {
    suspend fun findPartyByAccountId(accountId: UUID): UUID?
}

/**
 * The read path of the counters: a pure query, no side effect, no other table (ADR-0371 §1, §2).
 *
 * This is the port a pack evaluation reads, and the one a pre-send admission step (ADR-0374) is
 * meant to read later. One currency and one band per question, so an answer can never mix
 * currencies; [MovementCountQuery.direction] and [MovementCountQuery.rail] narrow it when set.
 */
fun interface MovementCounterReader {
    /** Movements counted in buckets starting in `[fromInclusive, toExclusive)`; 0 when there are none. */
    suspend fun countMovements(query: MovementCountQuery): Long
}

data class MovementCountQuery(
    val partyId: UUID,
    val currency: String,
    /** A band code of the scheme, or `CounterScheme.ALL_BAND` for every amount. */
    val bandCode: String,
    val schemeRevision: Int,
    val fromInclusive: Instant,
    val toExclusive: Instant,
    val direction: MovementDirection? = null,
    val rail: String? = null,
) {
    init {
        require(toExclusive > fromInclusive) { "the window is empty" }
    }
}
