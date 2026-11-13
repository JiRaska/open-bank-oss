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
 * @param refused registrations the registry refused for a reason a retry cannot change; each is
 *   an approved subject that is NOT monitored
 * @param unscreenedApprovedCases approved cases that carry no screening at all: nobody on them
 *   can be registered, because nobody was screened
 */
data class MonitoringStats(
    val registered: Long,
    val pending: Long,
    val oldestPendingAgeSeconds: Long,
    val refused: Long,
    val openReviews: Long,
    val oldestOpenReviewAgeSeconds: Long,
    val exitsRequired: Long,
    val unscreenedApprovedCases: Long,
)

/**
 * The durable desired-state store behind ongoing-monitoring registration (ADR-0376, kyb side).
 *
 * [syncInTransaction] is the only writer of the desired state and runs INSIDE the transaction of
 * the case write, so "the case is approved" and "its subjects are to be registered" commit
 * together or not at all. Everything else is the relay's bookkeeping, and every one of those
 * writes is a single conditional statement: none reads a row and writes it back.
 */
@Suppress("TooManyFunctions") // one conditional statement per thing its three callers ask; none composes another
interface MonitoringRegistrationStore {
    /**
     * Brings the rows of [caseId] to [desiredRefs] in ONE statement: a missing reference is added
     * as REGISTERED, a registered one no longer desired becomes WITHDRAWN, a withdrawn one desired
     * again becomes REGISTERED. Every row it changes is due immediately. Two writers of the same
     * case cannot collide on a row: the insert yields to an existing one. Must be called in an
     * open transaction.
     */
    fun syncInTransaction(caseId: UUID, desiredRefs: Set<String>, now: Instant): Uni<Void>

    /** Rows still to be sent whose next attempt is due, oldest first. */
    suspend fun due(now: Instant, limit: Int): List<MonitoringRegistration>

    /** Records a delivery. False when the row has moved on to a newer generation meanwhile. */
    suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean

    /** Records a failed attempt that is worth repeating, and when. [reason] is a short machine code. */
    suspend fun markFailed(registration: MonitoringRegistration, now: Instant, nextAttemptAt: Instant, reason: String)

    /**
     * Records a refusal that repeating cannot change (the registry will not take this subject as
     * it is). The row leaves the retry loop and the backlog; it is tried again when the case
     * changes or when it is re-asserted.
     */
    suspend fun markRefused(registration: MonitoringRegistration, now: Instant, reason: String)

    /** Rows of every case that hold [subjectRef]. */
    suspend fun findBySubjectRef(subjectRef: String): List<MonitoringRegistration>

    /** Whether a row of ANOTHER case still wants [subjectRef] registered. */
    suspend fun wantedElsewhere(subjectRef: String, exceptCaseId: UUID): Boolean

    /**
     * Makes every row of ANOTHER case that wants [subjectRef] registered due again, at a new
     * generation. Called after a withdrawal of a shared reference: whatever was in flight for
     * those rows can no longer be recorded as delivered, so they are sent again.
     */
    suspend fun reassertElsewhere(subjectRef: String, exceptCaseId: UUID, now: Instant)

    /**
     * Makes registered rows that were last confirmed, or refused, before [before] due again.
     * Returns how many.
     */
    suspend fun requeueSettledBefore(before: Instant, now: Instant, limit: Int): Int

    /**
     * Approved cases that have a screening and no row at all: approved before registration
     * existed. A case that already has rows is kept in step by its own writes, not from here.
     */
    suspend fun casesToBackfill(limit: Int): List<UUID>

    /** Delivery state per reference of one case: `PENDING` | `SYNCED` | `REFUSED`, for the operator's view. */
    suspend fun statesOf(caseId: UUID): Map<String, String>

    suspend fun stats(now: Instant): MonitoringStats
}

/** Why a registry call did not go through, by what the relay must do about it. */
enum class RegistryFailure {
    /** Unreachable, timed out, 5xx, throttled: repeating it may work. */
    TRANSIENT,

    /** The registry does not accept this caller (401/403): nothing is registered until that is fixed. */
    UNAUTHORISED,

    /** The registry will not take this subject as it is (any other 4xx): repeating it cannot work. */
    REFUSED,
}

/** The registry could not be told. [reason] is a short machine code safe to store and to tag a metric with. */
class MonitoredSubjectRegistryException(val reason: String, val kind: RegistryFailure, cause: Throwable?) :
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
    /** One relay attempt: [outcome] is `registered` | `withdrawn` | `failed` | `unauthorised` | `refused`; [reason] unless delivered. */
    fun registrationAttempted(outcome: String, reason: String = "none")

    /** One record on the findings channel by what became of it; see `MonitoringFindingOutcome`. */
    fun findingHandled(outcome: String)

    /** One scheduled run: `completed` | `disabled` | `failed`. */
    fun runFinished(outcome: String)

    fun observed(stats: MonitoringStats)
}
