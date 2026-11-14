// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import com.openbank.kyb.domain.model.MonitoringFinding
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.util.UUID

/** What kyb wants the monitored-subject registry to hold for one subject. */
enum class MonitoringDesired { REGISTERED, WITHDRAWN }

/**
 * One row of the registration store: the desired state of one subject of one case, the opaque
 * reference it is registered under, and whether sanctions-service has been told.
 *
 * @param subjectKey the subject within the case; it never leaves this service
 * @param subjectRef a random value minted by the database when the row is created; the only
 *   identifier of the subject that the registry, the URL and the findings topic ever carry
 * @param generation moves on every change of [desired]
 * @param claim set on a row handed out by [MonitoringRegistrationStore.claimNext]: the lease this
 *   relay holds, and the token every later write about the delivery is fenced on
 */
data class MonitoringRegistration(
    val id: Long,
    val caseId: UUID,
    val subjectKey: String,
    val subjectRef: String,
    val desired: MonitoringDesired,
    val generation: Long,
    val attemptCount: Int,
    val claim: Instant? = null,
)

/**
 * What the store looks like right now, read from stored timestamps so that a restarted pod reports
 * the same ages as the one it replaced.
 */
data class MonitoringStats(
    val registered: Long,
    val pending: Long,
    val oldestPendingAgeSeconds: Long,
    /** Registrations the registry refused for a reason a retry cannot change: approved, NOT monitored. */
    val refused: Long,
    val openReviews: Long,
    val oldestOpenReviewAgeSeconds: Long,
    val exitsOwed: Long,
    val oldestExitOwedAgeSeconds: Long,
    /** Approved cases with no screening at all: nobody on them can be registered. */
    val unscreenedApprovedCases: Long,
    /** Cases whose last writer did not bring the store up to date (an older release, or none yet). */
    val unreconciledCases: Long,
)

/**
 * The durable store behind ongoing monitoring (ADR-0376, kyb side): which subject must be
 * registered under which reference, and the findings about them.
 *
 * It lives in tables that only this code writes. A release that knows nothing of monitoring
 * rewrites a case's screening document on every write and would drop anything kept there; it
 * cannot drop a reference or a finding kept here.
 *
 * The two `InTransaction` functions run INSIDE the transaction of the case write. Everything
 * else is the relay's bookkeeping, and every one of those writes is a single conditional
 * statement about a single row: none reads a row and writes it back, and none can deadlock.
 */
@Suppress("TooManyFunctions") // one single-row statement per thing its three callers ask; none composes another
interface MonitoringRegistrationStore {
    /**
     * Brings the rows of [caseId] to [desiredKeys] in ONE statement: a missing subject is added
     * as REGISTERED under a new random reference, a registered one no longer desired becomes
     * WITHDRAWN, a withdrawn one desired again becomes REGISTERED under the reference it had, and
     * a still-desired subject the registry refused is made due again. Two writers of one case
     * cannot collide on a row: the insert yields to an existing one.
     */
    fun syncInTransaction(caseId: UUID, desiredKeys: Set<String>, now: Instant): Uni<Void>

    /**
     * Stores [findings] with their decisions, in ONE statement, keyed by (case, finding). Fails
     * with [FindingDecisionConflictException] — and so fails the whole case write — unless every
     * one of them ends up stored as given: a stored decision is never replaced by a different one.
     */
    fun saveFindingsInTransaction(caseId: UUID, findings: List<MonitoringFinding>): Uni<Void>

    suspend fun findingsOf(caseId: UUID): List<MonitoringFinding>

    /**
     * Hands out ONE due row to this caller alone, leased until [leaseUntil]; null when none is
     * due. While the lease holds no other relay delivers the row, so two deliveries of one row
     * never overlap and a withdrawal can only be sent after the registration before it finished.
     */
    suspend fun claimNext(now: Instant, leaseUntil: Instant): MonitoringRegistration?

    /** Records the delivery [registration] was claimed for. False when the row changed meanwhile; the claim is released either way. */
    suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean

    /** Records a failed attempt that is worth repeating, and when. [reason] is a short machine code. */
    suspend fun markFailed(registration: MonitoringRegistration, now: Instant, nextAttemptAt: Instant, reason: String)

    /**
     * Records a refusal that repeating cannot change. The row leaves the retry loop and the
     * backlog; it is tried again when its case is written or when it is re-asserted.
     */
    suspend fun markRefused(registration: MonitoringRegistration, now: Instant, reason: String)

    /** The row registered under [subjectRef], if any. A reference belongs to exactly one subject of one case. */
    suspend fun findBySubjectRef(subjectRef: String): MonitoringRegistration?

    /** Makes registered rows that were last confirmed, or refused, before [before] due again. Returns how many. */
    suspend fun requeueSettledBefore(before: Instant, now: Instant, limit: Int): Int

    /**
     * Cases whose last writer did not synchronise this store: written by a release that does not
     * know it, or never written since it exists. The relay writes each of them again.
     */
    suspend fun casesToReconcile(limit: Int): List<UUID>

    /** Delivery state per subject key of one case: `PENDING` | `SYNCED` | `REFUSED`, for the operator's view. */
    suspend fun statesOf(caseId: UUID): Map<String, String>

    suspend fun stats(now: Instant): MonitoringStats
}

/** Why a registry call did not go through, by what the relay must do about it. */
enum class RegistryFailure {
    /** Anything that may pass by itself or by a deployment: unreachable, timeout, 5xx, 404, 409, 429. */
    TRANSIENT,

    /** The registry does not accept this caller (401/403): nothing is registered until that is fixed. */
    UNAUTHORISED,

    /** The registry's validation rejects this subject as it is (400/422): repeating it cannot work. */
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

/**
 * A stored decision about a finding differs from the one the case being written carries —
 * disposition, decider, time or reason. Not a lost race (that is [ConcurrentCaseUpdateException],
 * which a re-read cures): re-reading cannot reconcile two decisions, so nothing of the case write
 * commits, a delivery is dead-lettered and an operator request answers 409.
 */
class FindingDecisionConflictException(caseId: UUID) :
    RuntimeException("a stored monitoring decision on kyb case $caseId differs from the one being written")
