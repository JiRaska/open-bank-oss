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
 *  1. every write of an approved case brings `kyb_monitored_subjects` to the case's monitored
 *     subjects in the SAME transaction (`BusinessOnboardingCaseRepository.update`), so an approval
 *     cannot commit without its registrations being owed;
 *  2. [run] sends what is owed, one claimed row at a time. `PUT` and `DELETE` are idempotent in
 *     the registry, so a failure that may pass is retried with backoff for as long as it takes;
 *     a subject the registry's validation REFUSES leaves the retry loop into its own state;
 *  3. [run] also repairs: a case whose last writer did not synchronise the store (an older
 *     release during a rolling deploy or after a rollback, or a case approved before the store
 *     existed) is written again, and a settled registration is sent again once it is older than
 *     `reassert-after`.
 *
 * A subject is registered under a random reference the store mints. The name the registry needs
 * is read from the case at send time, so no second copy of it exists here.
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

    /** One bounded pass: reconcile, re-assert, then send what is due. */
    suspend fun run(): MonitoringRunOutcome {
        if (!settings.enabled) return MonitoringRunOutcome.DISABLED
        val now = Instant.now(clock)
        store.casesToReconcile(settings.batchSize).forEach { id -> isolated("reconciling case $id") { reconcile(id) } }
        store.requeueSettledBefore(now.minus(settings.reassertAfter), now, settings.batchSize)
        repeat(settings.batchSize) {
            // Claimed one at a time, and the clock read each time: a row the reconciliation
            // just created is due from the moment it was written.
            val claimedAt = Instant.now(clock)
            val row = store.claimNext(claimedAt, claimedAt.plus(LEASE)) ?: return MonitoringRunOutcome.COMPLETED
            isolated("row ${row.id}") { deliver(row) }
        }
        return MonitoringRunOutcome.COMPLETED
    }

    /**
     * One case or one row failing must not cost the others their turn: the next run would start
     * with the same one and never get past it. The failure is counted; the row stays owed and its
     * lease runs out.
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
     * Writing the case through the repository is what brings the store to it; nothing is derived
     * here. A case somebody else writes in the meantime was synchronised by that write.
     */
    private suspend fun reconcile(id: UUID) {
        val case = cases.findById(id) ?: return
        try {
            cases.update(case, null)
        } catch (e: ConcurrentCaseUpdateException) {
            log.debugf("kyb case %s changed while it was being reconciled (%s)", id, e.message)
        }
    }

    private suspend fun deliver(row: MonitoringRegistration) {
        val now = Instant.now(clock)
        try {
            if (row.desired == MonitoringDesired.WITHDRAWN) {
                registry.withdraw(row.subjectRef)
                if (store.markSynced(row, now)) metrics.registrationAttempted(WITHDRAWN)
                return
            }
            val subject = cases.findById(row.caseId)?.monitoredSubjects?.firstOrNull { it.key == row.subjectKey }
            if (subject == null) {
                // The store wants a subject registered that the case does not carry: the case was
                // last written by something that did not synchronise. The registry is NOT touched
                // on this evidence; the reconciliation above settles what the row should be.
                store.markFailed(row, now, now.plus(backoff(row.attemptCount)), REASON_DRIFT)
                metrics.registrationAttempted(FAILED, REASON_DRIFT)
                return
            }
            registry.register(row.subjectRef, subject.organisation, subject.name)
            if (store.markSynced(row, now)) metrics.registrationAttempted(REGISTERED)
        } catch (e: MonitoredSubjectRegistryException) {
            // Only the row id and the reason: a reference or a name in a log line is a finding waiting to leak.
            log.warnf("monitoring registration row %d (%s) failed: %s", row.id, row.desired, e.reason)
            when (e.kind) {
                // The registry's validation will not take this subject as it is. Repeating the
                // call would only keep the backlog alert lit over something a retry cannot fix.
                RegistryFailure.REFUSED -> store.markRefused(row, now, e.reason)
                else -> store.markFailed(row, now, now.plus(backoff(row.attemptCount)), e.reason)
            }
            metrics.registrationAttempted(e.kind.outcome, e.reason)
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

        private const val BACKOFF_BASE_SECONDS = 30L
        private const val BACKOFF_MAX_SECONDS = 3600L
        private const val BACKOFF_MAX_SHIFT = 7

        /** Well over the registry call's own timeout: a lease never runs out under a call in flight. */
        val LEASE: Duration = Duration.ofSeconds(60)

        /** 30 s doubling to one hour, and then hourly for as long as it takes. */
        fun backoff(attemptsSoFar: Int): Duration = Duration.ofSeconds(
            minOf(BACKOFF_MAX_SECONDS, BACKOFF_BASE_SECONDS shl attemptsSoFar.coerceIn(0, BACKOFF_MAX_SHIFT)),
        )
    }
}
