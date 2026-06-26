// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.analytics

import java.time.Duration
import java.time.Instant

/**
 * A request to (re)load a window of events into the bronze layer (ADR-0022).
 *
 * This is how the warehouse recovers from the cases the live stream alone cannot:
 *  - **Outage longer than Kafka retention** — replay from the durable source of record (the
 *    per-service outbox / its archive), NOT Kafka, for [from]..[to].
 *  - **Initial load** — seed pre-existing OLTP state ([IngestSource.INITIAL_LOAD]).
 *  - **Correction** — re-publish a fixed batch with higher versions ([IngestSource.CORRECTION]).
 *
 * Re-ingestion is always safe: bronze dedupes on `eventId` and is last-writer-wins on
 * `aggregateVersion`, so a backfill overlapping live data converges to the same state.
 */
data class BackfillRequest(
    val source: IngestSource,
    /** Inclusive lower bound (business `occurredAt`). */
    val from: Instant,
    /** Inclusive upper bound. */
    val to: Instant,
    /** Optional narrowing to a single aggregate type, e.g. only `TRANSACTION`. Null = all. */
    val aggregateType: String? = null,
    /** Optional narrowing to a single aggregate id (e.g. fix one account). Null = all. */
    val aggregateId: String? = null,
    /** Human reason — recorded for audit (who reloaded what, and why). */
    val reason: String,
    /** Operator who requested it (from the security context). */
    val requestedBy: String,
) {
    init {
        require(source != IngestSource.STREAM) { "STREAM is the live path, not a backfill source" }
        require(!from.isAfter(to)) { "from must be <= to" }
    }
}

/** One bounded sub-window of a [BackfillRequest], so a long reload is processed in chunks. */
data class BackfillWindow(val from: Instant, val to: Instant)

/**
 * Splits a [BackfillRequest] into bounded time windows.
 *
 * A 10-year reload must never be a single unbounded scan/insert — it would blow memory and hold one
 * giant transaction. Chunking gives restartable, independently-committable units (a failure resumes
 * from the last completed window) and keeps each ClickHouse insert a sane size. Pure and deterministic.
 */
object BackfillPlanner {

    fun chunk(request: BackfillRequest, window: Duration): List<BackfillWindow> {
        require(!window.isZero && !window.isNegative) { "window must be positive" }
        val windows = ArrayList<BackfillWindow>()
        var start = request.from
        while (start.isBefore(request.to)) {
            val end = minOf(start.plus(window), request.to)
            windows.add(BackfillWindow(start, end))
            start = end
        }
        // Degenerate from == to still yields one zero-width window so a point reload runs once.
        if (windows.isEmpty()) windows.add(BackfillWindow(request.from, request.to))
        return windows
    }
}
