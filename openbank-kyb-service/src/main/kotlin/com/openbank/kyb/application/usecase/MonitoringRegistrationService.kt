// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.ConcurrentCaseUpdateException
import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryPort
import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.RegistryFailure
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What one scheduled run did. `DISABLED` is its own value so an idle run never counts as work done. */
enum class MonitoringRunOutcome { COMPLETED, DISABLED }

/**
 * Keeps the monitored-subject registry of sanctions-service in step with the approved business
 * cases (ADR-0376 D1, kyb side).
 *
 * The mechanism is a desired-state store plus a relay, not a call from the approval request:
 *
 *  1. every write of a case brings `kyb_monitored_subjects` to the case's monitored subjects in
 *     the SAME transaction (`BusinessOnboardingCaseRepository.update`), so an approval cannot
 *     commit without its registrations being owed, and a rejection cannot commit without its
 *     withdrawals being owed;
 *  2. [run] sends what is owed. `PUT` and `DELETE` are idempotent in the registry, so a row that
 *     failed for a reason that may pass (outage, timeout, a caller the registry does not accept
 *     yet) is retried with backoff for as long as it takes. A subject the registry REFUSES as it
 *     is leaves the retry loop into its own state, counted and shown to an operator: retrying it
 *     hourly forever would only hide the next real backlog behind a permanent one;
 *  3. [run] also repairs: cases approved before this existed are backfilled, and a confirmed
 *     registration is sent again once it is older than `reassert-after`.
 *
 * The store holds references only. The name the registry needs is read from the case at send
 * time, so it is the name on the case NOW and no second copy of it exists here.
 *
 * `@Startup` so the "switched off" warning is printed at boot and not on the first run of a lazy
 * bean.
 */
@Startup
@ApplicationScoped
class MonitoringRegistrationService {

    @Inject lateinit var store: MonitoringRegistrationStore

    @Inject lateinit var cases: BusinessOnboardingCaseRepository

    @Inject lateinit var registry: MonitoredSubjectRegistryPort

    @Inject lateinit var metrics: KybMonitoringMetricsPort

    @Inject lateinit var settings: MonitoringSettings

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(MonitoringRegistrationService::class.java)

    @PostConstruct
    fun announce() {
        if (!settings.enabled) {
            log.warn(
                "ongoing sanctions/PEP monitoring registration is SWITCHED OFF " +
                    "(openbank.kyb.monitoring.enabled=false): approved business cases are NOT registered with " +
                    "the monitored-subject registry, so a later listing of an entity, a representative or a " +
                    "beneficial owner raises nothing. Registrations owed are recorded and are sent once it is on",
            )
        }
    }

    /** One bounded pass: backfill, re-assert, then send what is due. */
    suspend fun run(): MonitoringRunOutcome {
        if (!settings.enabled) return MonitoringRunOutcome.DISABLED
        val now = Instant.now(clock)
        store.casesToBackfill(settings.batchSize).forEach { id -> isolated("backfill of case $id") { backfill(id) } }
        store.requeueSettledBefore(now.minus(settings.reassertAfter), now, settings.batchSize)
        // Read the clock again: a row the backfill just created is due from the moment it was written.
        store.due(Instant.now(clock), settings.batchSize).forEach { row -> isolated("row ${row.id}") { deliver(row) } }
        return MonitoringRunOutcome.COMPLETED
    }

    /**
     * One case or one row failing must not cost the others their turn: the next run would start
     * with the same one and never get past it. The failure is counted; the row stays owed.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun isolated(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            log.warnf(e, "monitoring registration: %s failed, continuing with the rest", what)
            metrics.registrationAttempted(FAILED, REASON_INTERNAL)
        }
    }

    /**
     * An approved case with no row in the store: approved before registration shipped. Writing
     * the case through the repository is what creates the rows; nothing is inserted from here.
     */
    private suspend fun backfill(id: UUID) {
        val case = cases.findById(id)?.takeIf { it.monitored } ?: return
        rewrite(case.withMonitoringRefs(::newSubjectRef))
    }

    private suspend fun deliver(row: MonitoringRegistration) {
        val now = Instant.now(clock)
        val case = cases.findById(row.caseId)
        val subject = case?.monitoredSubjects?.firstOrNull { it.ref == row.subjectRef }
        try {
            when {
                row.desired == MonitoringDesired.WITHDRAWN -> withdraw(row, now)
                subject != null -> {
                    registry.register(row.subjectRef, subject.organisation, subject.name)
                    if (store.markSynced(row, now)) metrics.registrationAttempted(REGISTERED)
                }
                else -> driftFound(row, case, now)
            }
        } catch (e: MonitoredSubjectRegistryException) {
            // Only the row id and the reason: a reference or a name in a log line is a finding waiting to leak.
            log.warnf("monitoring registration row %d (%s) failed: %s", row.id, row.desired, e.reason)
            when (e.kind) {
                // The registry will not take this subject as it is. Repeating the call hourly
                // would only keep the backlog alert lit over something a retry cannot fix.
                RegistryFailure.REFUSED -> store.markRefused(row, now, e.reason)
                else -> store.markFailed(row, now, now.plus(backoff(row.attemptCount)), e.reason)
            }
            metrics.registrationAttempted(e.kind.outcome, e.reason)
        }
    }

    /**
     * The registry keys a subject by reference alone, and a subject screened by an earlier
     * release can share its reference with another case of the same entity. Such a reference is
     * not deleted while another case wants it; and because that other case's registration may be
     * in flight right now, every one of them is sent again afterwards, at a new generation, so a
     * delete that landed after its PUT cannot leave it recorded as delivered.
     */
    private suspend fun withdraw(row: MonitoringRegistration, now: Instant) {
        if (!store.wantedElsewhere(row.subjectRef, row.caseId)) registry.withdraw(row.subjectRef)
        store.reassertElsewhere(row.subjectRef, row.caseId, now)
        if (store.markSynced(row, now)) metrics.registrationAttempted(WITHDRAWN)
    }

    /**
     * The store wants a subject registered that the case does not carry. The case is the
     * authority, so it is written again: that brings the row to WITHDRAWN (or keeps it, if a
     * concurrent write just added the subject), and the next run sends whichever it is. Nothing
     * is deleted from the registry on this evidence alone.
     */
    private suspend fun driftFound(row: MonitoringRegistration, case: BusinessOnboardingCase?, now: Instant) {
        log.warnf("monitoring registration row %d has no subject on its case; re-deriving", row.id)
        val reason = if (case == null) REASON_CASE_MISSING else REASON_DRIFT
        if (case != null) rewrite(case)
        store.markFailed(row, now, now.plus(backoff(row.attemptCount)), reason)
        metrics.registrationAttempted(FAILED, reason)
    }

    private suspend fun rewrite(case: BusinessOnboardingCase) {
        try {
            cases.update(case, null)
        } catch (e: ConcurrentCaseUpdateException) {
            // Somebody else wrote the case, and that write synchronised the store. Next run looks again.
            log.debugf("kyb case %s changed while its monitoring rows were re-derived (%s)", case.id, e.message)
        }
    }

    private val RegistryFailure.outcome: String
        get() = when (this) {
            RegistryFailure.TRANSIENT -> FAILED
            RegistryFailure.UNAUTHORISED -> UNAUTHORISED
            RegistryFailure.REFUSED -> REFUSED
        }

    companion object {
        const val REGISTERED = "registered"
        const val WITHDRAWN = "withdrawn"
        const val FAILED = "failed"
        const val UNAUTHORISED = "unauthorised"
        const val REFUSED = "refused"
        const val REASON_INTERNAL = "internal"
        const val REASON_DRIFT = "desired_state_drift"
        const val REASON_CASE_MISSING = "case_missing"

        private const val BACKOFF_BASE_SECONDS = 30L
        private const val BACKOFF_MAX_SECONDS = 3600L
        private const val BACKOFF_MAX_SHIFT = 7

        /** A random UUID (ADR-0106): the reference must reveal neither who the subject is nor when it was first screened. */
        fun newSubjectRef(): String = Ids.randomId().toString()

        /** 30 s doubling to one hour, and then hourly for as long as it takes. */
        fun backoff(attemptsSoFar: Int): Duration = Duration.ofSeconds(
            minOf(BACKOFF_MAX_SECONDS, BACKOFF_BASE_SECONDS shl attemptsSoFar.coerceIn(0, BACKOFF_MAX_SHIFT)),
        )
    }
}
