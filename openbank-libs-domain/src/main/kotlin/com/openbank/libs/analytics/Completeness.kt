// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

/**
 * Completeness checking for the bronze layer (ADR-0023, finding F5).
 *
 * Reconciliation ([Reconciliation.diff]) proves the warehouse's *current state* matches OLTP, but it
 * compares only the max version per aggregate. BCBS 239 §3 ("completeness") asks a stricter question:
 * did we lose any event *in between*? Because [AnalyticsEnvelope.aggregateVersion] is a monotonic,
 * gap-free sequence per aggregate at the source, a missing version in bronze is a provable lost event
 * — an invisible gap that current-state reconciliation would never reveal.
 *
 * This is pure sequence analysis over the bronze event log; the sink service exposes it as audit
 * evidence ("every aggregate's version sequence is contiguous up to its max").
 */
data class VersionGap(
    val key: AggregateKey,
    /** Versions expected but absent between the aggregate's min and max observed version. */
    val missingVersions: List<Long>,
)

data class CompletenessReport(val aggregatesChecked: Int, val gaps: List<VersionGap>) {
    val gapCount: Int get() = gaps.sumOf { it.missingVersions.size }
    val complete: Boolean get() = gaps.isEmpty()
    val status: String get() = if (complete) "COMPLETE" else "INCOMPLETE"
}

object Completeness {

    /**
     * Detects missing versions per aggregate. For each (aggregateType, aggregateId) the observed
     * versions must form a contiguous range from its minimum to its maximum; any integer in
     * `[min, max]` not present is a gap (a lost event).
     *
     * Note: this detects holes *within* the observed range. A gap at the very start (e.g. events
     * 1..3 never arrived but 4.. did) is caught by [expectedFromBase] which assumes aggregates begin
     * at version [base] (default 1). Duplicate versions (legitimate at-least-once / corrections) are
     * collapsed and never counted as gaps.
     */
    fun gaps(events: List<AnalyticsEnvelope>, base: Long = 1L): CompletenessReport {
        val byKey = events.groupBy { AggregateKey(it.aggregateType, it.aggregateId) }
        val gaps = ArrayList<VersionGap>()
        for ((key, group) in byKey) {
            val present = group.mapTo(HashSet()) { it.aggregateVersion }
            val max = present.max()
            val missing = (base..max).filter { it !in present }
            if (missing.isNotEmpty()) gaps.add(VersionGap(key, missing))
        }
        return CompletenessReport(aggregatesChecked = byKey.size, gaps = gaps)
    }

    /** Convenience alias documenting the base-version assumption used by [gaps]. */
    fun expectedFromBase(events: List<AnalyticsEnvelope>, base: Long): CompletenessReport = gaps(events, base)

    /**
     * Gap detection straight from a `AggregateKey -> versions-present` map — what a warehouse
     * `SELECT aggregate_type, aggregate_id, groupArray(aggregate_version) ... GROUP BY` returns. Lets
     * the completeness check transfer only versions (no payloads), like reconciliation.
     */
    fun gapsFromVersions(versionsByKey: Map<AggregateKey, Collection<Long>>, base: Long = 1L): CompletenessReport {
        val gaps = ArrayList<VersionGap>()
        for ((key, versions) in versionsByKey) {
            if (versions.isEmpty()) continue
            val present = versions.toHashSet()
            val max = present.max()
            val missing = (base..max).filter { it !in present }
            if (missing.isNotEmpty()) gaps.add(VersionGap(key, missing))
        }
        return CompletenessReport(aggregatesChecked = versionsByKey.size, gaps = gaps)
    }
}
