// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.analytics

/**
 * Pure, deterministic projection helpers over a batch of [AnalyticsEnvelope] records (ADR-0022).
 *
 * These encode the consistency rules the analytics layer relies on, in one tested place:
 *  - [dedupeByEventId] — collapses at-least-once Kafka duplicates (same `eventId`).
 *  - [latestPerAggregate] — last-writer-wins per aggregate, mirroring ClickHouse
 *    `ReplacingMergeTree(aggregateVersion) FINAL` so the in-app reconciliation job computes the
 *    *same* current-state view the warehouse serves.
 *
 * Used by the sink (pre-write dedupe) and the reconciliation job (OLTP-vs-warehouse drift check).
 * No framework dependencies — unit-tested like the saga/case state-machine primitives.
 */
object AnalyticsProjections {

    /** Keeps the first occurrence of each [AnalyticsEnvelope.eventId], preserving input order. */
    fun dedupeByEventId(events: List<AnalyticsEnvelope>): List<AnalyticsEnvelope> {
        val seen = HashSet<java.util.UUID>(events.size)
        return events.filter { seen.add(it.eventId) }
    }

    /**
     * Current state per aggregate: for each (aggregateType, aggregateId) the record with the
     * highest [AnalyticsEnvelope.aggregateVersion], breaking ties by [AnalyticsEnvelope.occurredAt]
     * then [AnalyticsEnvelope.ingestedAt] (deterministic for equal versions / out-of-order arrival).
     * Result order is unspecified.
     */
    fun latestPerAggregate(events: List<AnalyticsEnvelope>): List<AnalyticsEnvelope> =
        events.groupingBy { it.aggregateType to it.aggregateId }
            .reduce { _, acc, e -> if (LATEST.compare(e, acc) >= 0) e else acc }
            .values
            .toList()

    /**
     * **As-of / point-in-time** current state: the [latestPerAggregate] view restricted to events
     * whose [AnalyticsEnvelope.occurredAt] is at or before [instant].
     *
     * This is the building block for "report as of 2025-12-31" regulatory tie-outs. Because bronze
     * is a full event log, any historical cut-off can be recomputed deterministically — the same
     * query the ClickHouse `... FINAL WHERE occurred_at <= {t}` view serves.
     */
    fun asOf(events: List<AnalyticsEnvelope>, instant: java.time.Instant): List<AnalyticsEnvelope> =
        latestPerAggregate(events.filter { !it.occurredAt.isAfter(instant) })

    /**
     * Full **version history** (SCD2 building block) for one aggregate: every event for
     * (aggregateType, aggregateId), ordered oldest→newest by the same [LATEST] ordering used for
     * last-writer-wins. Consecutive rows form the valid-from/valid-to intervals of a slowly-changing
     * dimension; the last row is the current state. Duplicates on eventId should be removed first
     * (see [dedupeByEventId]).
     */
    fun history(events: List<AnalyticsEnvelope>, aggregateType: String, aggregateId: String): List<AnalyticsEnvelope> =
        events.filter { it.aggregateType == aggregateType && it.aggregateId == aggregateId }
            .sortedWith(LATEST)

    private val LATEST: Comparator<AnalyticsEnvelope> =
        compareBy<AnalyticsEnvelope> { it.aggregateVersion }
            .thenBy { it.occurredAt }
            .thenBy { it.ingestedAt }
}
