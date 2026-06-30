// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

/** Identity of an aggregate across the OLTP source and the warehouse. */
data class AggregateKey(val aggregateType: String, val aggregateId: String)

/**
 * Result of comparing OLTP source-of-record current state against the analytics warehouse.
 *
 * The comparison is on the **last-writer-wins authority** — the per-aggregate max
 * [AnalyticsEnvelope.aggregateVersion] — because that is exactly what the warehouse's
 * `ReplacingMergeTree ... FINAL` (silver) and [AnalyticsProjections.latestPerAggregate] both resolve
 * to. Equal versions ⇒ in sync; everything else is actionable drift.
 */
data class ReconciliationDiff(
    val checked: Int,
    /** In OLTP but absent from the warehouse — a gap to **backfill** (see [IngestSource.BACKFILL]). */
    val missingInWarehouse: List<AggregateKey>,
    /** In the warehouse but absent from OLTP — orphan/erased rows to investigate. */
    val missingInSource: List<AggregateKey>,
    /** Present in both but versions differ — lag or a lost update; backfill the newer side. */
    val versionMismatch: List<AggregateKey>,
) {
    val driftCount: Int get() = missingInWarehouse.size + missingInSource.size + versionMismatch.size
    val inSync: Boolean get() = driftCount == 0

    /** Audit-friendly status string surfaced by the reconciliation endpoint. */
    val status: String get() = if (inSync) "IN_SYNC" else "DRIFT"
}

/**
 * Pure, deterministic reconciliation of OLTP-vs-warehouse current state (ADR-0022).
 *
 * Both sides are reduced to `AggregateKey -> maxVersion` maps (cheap: a `GROUP BY ... max(version)`
 * on each side, no row-by-row payload transfer) and compared here. Used by the periodic drift job
 * and the on-demand `ROLE_AUDITOR` trigger to produce point-in-time evidence that the warehouse
 * equals the source of record — and to drive automatic backfill of whatever is missing/behind.
 */
object Reconciliation {

    fun diff(source: Map<AggregateKey, Long>, warehouse: Map<AggregateKey, Long>): ReconciliationDiff {
        val missingInWarehouse = ArrayList<AggregateKey>()
        val missingInSource = ArrayList<AggregateKey>()
        val versionMismatch = ArrayList<AggregateKey>()

        for ((key, srcVersion) in source) {
            val whVersion = warehouse[key]
            when {
                whVersion == null -> missingInWarehouse.add(key)
                whVersion != srcVersion -> versionMismatch.add(key)
            }
        }
        for (key in warehouse.keys) {
            if (key !in source) missingInSource.add(key)
        }
        return ReconciliationDiff(
            checked = (source.keys + warehouse.keys).size,
            missingInWarehouse = missingInWarehouse,
            missingInSource = missingInSource,
            versionMismatch = versionMismatch,
        )
    }

    /** Convenience: project a batch of envelopes to the `AggregateKey -> maxVersion` map for one side. */
    fun versionMap(events: List<AnalyticsEnvelope>): Map<AggregateKey, Long> =
        AnalyticsProjections.latestPerAggregate(events)
            .associate { AggregateKey(it.aggregateType, it.aggregateId) to it.aggregateVersion }

    /**
     * Row-count reconciliation per aggregate type (ADR-0023, finding F4). Version reconciliation
     * proves current-state agreement; a count tie-out independently catches whole-aggregate loss
     * (an aggregate present on neither side's max-version comparison because it is missing in both
     * the warehouse *and* the version map). Cheap: a `GROUP BY aggregate_type count()` on each side.
     */
    fun countDiff(source: Map<String, Long>, warehouse: Map<String, Long>): Map<String, CountDelta> =
        (source.keys + warehouse.keys).associateWith { type ->
            CountDelta(source = source[type] ?: 0L, warehouse = warehouse[type] ?: 0L)
        }

    /**
     * A stable, signable fingerprint of a reconciliation outcome so the *evidence itself* is
     * tamper-evident when sealed to the WORM archive (BCBS 239 audit trail). Deterministic over the
     * sorted drift keys — re-running on the same diff yields the same fingerprint.
     */
    fun fingerprint(diff: ReconciliationDiff): String {
        val canonical = buildString {
            append(diff.checked).append('|')
            append(diff.missingInWarehouse.sortedKeys()).append('|')
            append(diff.missingInSource.sortedKeys()).append('|')
            append(diff.versionMismatch.sortedKeys())
        }
        return AnalyticsIntegrity.recordHashOfString(canonical)
    }

    private fun List<AggregateKey>.sortedKeys(): String =
        map { "${it.aggregateType}/${it.aggregateId}" }.sorted().joinToString(",")
}

/** Per-type row counts on each side and their delta. [inSync] when source and warehouse agree. */
data class CountDelta(val source: Long, val warehouse: Long) {
    val delta: Long get() = source - warehouse
    val inSync: Boolean get() = delta == 0L
}
