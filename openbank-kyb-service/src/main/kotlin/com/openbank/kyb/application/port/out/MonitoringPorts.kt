// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import io.smallrye.mutiny.Uni
import java.time.Instant
import java.util.UUID

/** What kyb wants the monitored-subject registry to hold for one subject. */
enum class MonitoringDesired { REGISTERED, WITHDRAWN }

/**
 * One row of the registration store: the desired state of one subject of one case and whether
 * sanctions-service has been told. It carries the opaque reference only, never a name.
 *
 * @param generation moves on every change of [desired]; a delivery is recorded only against the
 *   generation it was sent for
 */
data class MonitoringRegistration(
    val id: Long,
    val caseId: UUID,
    val subjectRef: String,
    val desired: MonitoringDesired,
    val generation: Long,
    val attemptCount: Int,
)

/**
 * What the store looks like right now, read from stored timestamps so that a restarted pod reports
 * the same ages as the one it replaced.
 *
 * @param unscreenedApprovedCases approved cases that carry no screening at all: nobody on them
 *   can be registered, because nobody was screened
 */
data class MonitoringStats(
    val registered: Long,
    val pending: Long,
    val oldestPendingAgeSeconds: Long,
    val openReviews: Long,
    val oldestOpenReviewAgeSeconds: Long,
    val unscreenedApprovedCases: Long,
)

/**
 * The durable desired-state store behind ongoing-monitoring registration (ADR-0376, kyb side).
 *
 * [syncInTransaction] is the only writer of the desired state and runs INSIDE the transaction of
 * the case write, so "the case is approved" and "its subjects are to be registered" commit
 * together or not at all. Everything else is the relay's bookkeeping.
 */
interface MonitoringRegistrationStore {
    /**
     * Brings the rows of [caseId] to [desiredRefs]: a missing reference is added as REGISTERED, a
     * registered one no longer desired becomes WITHDRAWN, a withdrawn one desired again becomes
     * REGISTERED. Every row it changes is due immediately. Must be called in an open transaction.
     */
    fun syncInTransaction(caseId: UUID, desiredRefs: Set<String>, now: Instant): Uni<Void>

    /** Rows still to be sent whose next attempt is due, oldest first. */
    suspend fun due(now: Instant, limit: Int): List<MonitoringRegistration>

    /** Records a delivery. False when the row has moved on to a newer generation meanwhile. */
    suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean

    /** Records a failed attempt and when to try again. [reason] is a short machine code. */
    suspend fun markFailed(registration: MonitoringRegistration, now: Instant, nextAttemptAt: Instant, reason: String)

    /** Rows of every case that hold [subjectRef]. */
    suspend fun findBySubjectRef(subjectRef: String): List<MonitoringRegistration>

    /** Whether a row of ANOTHER case still wants [subjectRef] registered. */
    suspend fun wantedElsewhere(subjectRef: String, exceptCaseId: UUID): Boolean

    /**
     * Makes registered rows whose last confirmation is older than [confirmedBefore] due again, so
     * the relay re-asserts them. Returns how many.
     */
    suspend fun requeueConfirmedBefore(confirmedBefore: Instant, now: Instant, limit: Int): Int

    /**
     * Approved cases with a screening whose current subjects are not all in the store: approved
     * before registration existed, or written by a path that did not go through it.
     */
    suspend fun casesToBackfill(limit: Int): List<UUID>

    suspend fun stats(now: Instant): MonitoringStats
}

/** The registry could not be told. [reason] is a short machine code safe to store and to tag a metric with. */
class MonitoredSubjectRegistryException(val reason: String, cause: Throwable?) :
    RuntimeException("monitored-subject registry call failed: $reason", cause)

/**
 * Outbound port to the monitored-subject registry of `openbank-sanctions-service`
 * (`PUT` / `DELETE /api/v3/sanctions/monitored-subjects/{subjectRef}`, ADR-0376 D1). Both
 * operations are idempotent on the registry's side, which is what makes blind retry safe.
 *
 * @throws MonitoredSubjectRegistryException on every failure, including a refusal
 */
interface MonitoredSubjectRegistryPort {
    /** Sends the same attributes the subject's onboarding screen sent: entity type and name. */
    suspend fun register(subjectRef: String, organisation: Boolean, name: String)

    suspend fun withdraw(subjectRef: String)
}

/** Counters and gauges of ongoing monitoring; a port so the use cases stay framework-free. */
interface KybMonitoringMetricsPort {
    /** One relay attempt: [outcome] is `registered` | `withdrawn` | `failed`; [reason] only for a failure. */
    fun registrationAttempted(outcome: String, reason: String = "none")

    /** One record on the findings channel by what became of it; see `MonitoringFindingOutcome`. */
    fun findingHandled(outcome: String)

    /** One scheduled run: `completed` | `disabled` | `failed`. */
    fun runFinished(outcome: String)

    fun observed(stats: MonitoringStats)
}
