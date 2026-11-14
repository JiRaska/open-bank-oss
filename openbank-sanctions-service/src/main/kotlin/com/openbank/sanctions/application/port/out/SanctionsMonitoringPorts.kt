// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.MonitoringFinding
import com.openbank.sanctions.domain.model.SanctionsListType
import java.time.Duration
import java.util.UUID

class MonitoredSubjectUpsert(val subject: MonitoredSubject, val created: Boolean)

/** A registry row whose names reach [score] against one list entry. */
class MonitoredSubjectCandidate(val subject: MonitoredSubject, val score: Double)

/** Registry figures for the gauges; [oldestSweepAgeSeconds] is 0 for an empty registry. */
class MonitoringRegistryStats(val subjects: Long, val oldestSweepAgeSeconds: Long)

/** Persistence of the monitored-subject registry. */
interface MonitoredSubjectRepository {
    /** Insert, or replace the attributes; a replacement makes the subject due for a baseline sweep. */
    suspend fun upsert(subject: MonitoredSubject, searchNames: List<String>): MonitoredSubjectUpsert

    /** Deletes the subject [ownerService] registered under [subjectRef], and no other owner's. */
    suspend fun delete(ownerService: String, subjectRef: String): Boolean

    /**
     * Subjects whose names match [entrySearchText] at [threshold] or above, by the same
     * `word_similarity(name, search_text)` the live screen computes. At most [limit] rows.
     *
     * An implementation may narrow the candidates through an index, but the answer is the one a
     * pass over the whole registry gives: every row returned carries the exact score.
     */
    suspend fun findMatching(entrySearchText: String, threshold: Double, limit: Int): List<MonitoredSubjectCandidate>

    /**
     * Lease up to [limit] subjects that are due: never swept, or last swept before the sweep
     * interval or before the last requested re-sweep. A lease older than [lease] is reclaimed.
     */
    suspend fun claimDueForSweep(interval: Duration, lease: Duration, limit: Int): List<MonitoredSubject>

    /** Record the sweep at the instant the lease was taken, i.e. before the lists were read. */
    suspend fun markSwept(id: UUID)

    suspend fun stats(): MonitoringRegistryStats
}

/** One committed change of a list entry waiting to be matched against the registry. */
class PendingEntryChange(
    val id: Long,
    val entryId: UUID,
    val listType: String,
    val externalId: String?,
    val active: Boolean,
)

/** The current state of a list entry, with the version of its names. */
class ListedEntry(
    val listType: SanctionsListType,
    val externalId: String?,
    val primaryName: String,
    val programs: List<String>,
    val searchText: String,
    val active: Boolean,
    val namesVersion: String,
)

/** Persistence of the delta queue and of findings. */
interface MonitoringWorkRepository {
    suspend fun pendingCount(): Long

    suspend fun claimPending(lease: Duration, limit: Int): List<PendingEntryChange>

    suspend fun completePending(id: Long)

    /**
     * Give up matching the queue entry by entry: drop what is queued and make every subject due
     * for a sweep, in one transaction, so a dropped change cannot be lost.
     */
    suspend fun requestResweep()

    suspend fun findEntry(entryId: UUID): ListedEntry?

    /** Version of the names of the active entry behind [listEntryRef], or null if there is none. */
    suspend fun namesVersion(listType: SanctionsListType, listEntryRef: String): String?

    /**
     * Persist [finding] and its outbox event in ONE transaction.
     *
     * @return false, having written nothing, when the same subject already has an open finding
     *   for this version of the entry, or when the subject is no longer registered.
     */
    suspend fun raise(finding: MonitoringFinding): Boolean

    /** Close the findings about an entry that left its list, so a relisting is a new finding. */
    suspend fun markDelisted(listType: String, listEntryRef: String): Int
}
