// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.OrphanedPartyDetector
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the #5698 orphaned-party reconciliation as metrics, and runs it on a cron.
 *
 *  - `openbank_kyc_orphaned_parties{service="kyc"}` — parties past the grace period with no KYC
 *    case. The alert (`KycPartiesWithoutCase`) reads this one.
 *  - `openbank_kyc_orphaned_parties_oldest_age_seconds{service="kyc"}` — how long the
 *    longest-stranded party has been waiting, `0` when there are none. Triage: separates a
 *    mis-configuration that started this morning from the months-old backlog #5698 found.
 *  - `openbank_kyc_orphan_detection_parties_scanned{service="kyc"}` — the DENOMINATOR.
 *
 * ### Why the denominator is published
 *
 * A scan that enumerated zero parties reports zero orphans, which is byte-identical to a healthy
 * register. Without `parties_scanned` there is no series anywhere that can tell "nothing is wrong"
 * apart from "this control is not looking at anything" — and a detection control that fails by
 * reporting clean is the exact failure mode #5698 is about.
 *
 * ### The value at t=0 on a cold pod (ADR-0237, #2239)
 *
 * Every gauge here is seeded to **0**, and the alert is `> 0`, so a freshly started pod cannot fire
 * it. This is the deliberate opposite of the `Instant.EPOCH` seeding that made
 * `openbank_workflow_last_success_age_seconds` read as decades on a cold pod and fire
 * `WorkflowLivenessStale` 15 minutes after every deploy for every daily workflow.
 *
 * A boot-time 0 is a genuine fourth state — "not yet measured", next to healthy/degraded/absent —
 * and it is not left ambiguous. It is resolved by the other two signals rather than by this gauge:
 * `parties_scanned` is also 0 until the first successful pass, and
 * [DomainMetrics.registerWorkflowLiveness] publishes a heartbeat whose age is seeded from
 * REGISTRATION time, so `WorkflowLivenessStale` fires if this job never completes a pass within 2x
 * its interval — without firing at boot, because the seed makes a fresh pod's age its own uptime.
 * So "no orphans" and "never ran" are distinguishable, and neither is alarming at t=0.
 *
 * ### Failure semantics
 *
 * A failed pass (party-service down, kyc-db down) leaves the previous values in place and does NOT
 * record a liveness success. Resetting to 0 on failure would be the same defect in miniature: an
 * unreachable party-service would publish "no orphans" and clear a firing alert. Staleness is what
 * the liveness heartbeat is for.
 *
 * Service-local [MeterRegistry] rather than a new method on the shared [DomainMetrics] facade: this
 * is a kyc-service signal, and putting it in libs would force a fleet-wide rebuild for it
 * (the [com.openbank.domestic.infrastructure.observability.DomesticPaymentStrandedGauge] precedent).
 */
@Startup
@ApplicationScoped
class OrphanedPartyGauge(
    private val detector: OrphanedPartyDetector,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    private val enabled: Boolean,
) {
    // Explicit @Inject constructor: MeterRegistry is optional (absent in slim test slices), and
    // without this ArC sees two constructors, registers no bean, and the @Startup hook silently
    // never runs — the DomesticPaymentStrandedGauge comment documents the same trap.
    @Inject
    constructor(
        detector: OrphanedPartyDetector,
        registryInstance: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
        @ConfigProperty(name = "openbank.kyc.orphan-detection.enabled", defaultValue = "true")
        enabled: Boolean,
    ) : this(
        detector,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        clock,
        domainMetrics,
        enabled,
    )

    private val log = Logger.getLogger(OrphanedPartyGauge::class.java)

    private val orphanCount = AtomicLong(0)
    private val oldestOrphanAgeSeconds = AtomicLong(0)
    private val partiesScanned = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        gauge(r, "openbank.kyc.orphaned.parties", orphanCount)
        gauge(r, "openbank.kyc.orphaned.parties.oldest.age.seconds", oldestOrphanAgeSeconds)
        gauge(r, "openbank.kyc.orphan.detection.parties.scanned", partiesScanned)
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        // Registered even when the job is disabled: a disabled control that publishes no heartbeat
        // is indistinguishable from a broken one, and the sentinel should be able to say which.
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    private fun gauge(r: MeterRegistry, name: String, holder: AtomicLong) {
        Gauge.builder(name, holder) { it.get().toDouble() }
            .tag("service", METRIC_SERVICE_TAG)
            .strongReference(true)
            .register(r)
    }

    /**
     * `suspend fun`, not a plain `fun` wrapping `runBlocking` — Quarkus invokes a plain `@Scheduled`
     * method on a bare `executor-thread` with no Vert.x context, so the first reactive Panache query
     * inside would throw `HR000068` and abort the pass silently, having done nothing. That defect
     * shipped five times in this fleet (#2148, #2187) and is invisible to any test that calls this
     * method directly, because a direct call supplies the very context the scheduler does not.
     * `OrphanedPartyDetectionSchedulerIT` drives the real cron for that reason.
     */
    // TooGenericExceptionCaught: a scheduler tick must never crash the runtime, and every fault
    // here (REST, DB, deserialisation) has the identical handling — keep the last known values and
    // let the liveness heartbeat go stale. Same rationale as BalanceReconciliationScheduler.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        cron = "{openbank.kyc.orphan-detection.cron:0 15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        if (!enabled) return
        try {
            val report = detector.detect()
            orphanCount.set(report.orphanCount.toLong())
            partiesScanned.set(report.partiesScanned)
            oldestOrphanAgeSeconds.set(
                report.oldestOrphanCreatedAt
                    ?.let { maxOf(0L, Duration.between(it, Instant.now(clock)).seconds) }
                    ?: 0L,
            )
            liveness?.recordSuccess()
            if (report.orphanCount > 0) {
                // The ids, not just the count: remediation is a manual replay per party (#5698), so
                // the log line has to be actionable on its own. Party ids are opaque identifiers,
                // not PII — no name, email or birth number is read by this control at all.
                log.warnf(
                    "[orphan-detection] %d of %d parties have NO KYC case (oldest stranded since %s): %s",
                    report.orphanCount,
                    report.partiesScanned,
                    report.oldestOrphanCreatedAt,
                    report.orphanedPartyIds.joinToString(),
                )
            } else {
                log.infof("[orphan-detection] All %d parties have a KYC case", report.partiesScanned)
            }
        } catch (e: Exception) {
            // Swallowed so a scheduler tick can never crash the runtime, but NOT reset to 0 and NOT
            // recorded as a success: an unreachable party-service must not publish "no orphans".
            // The liveness heartbeat going stale is what makes this visible.
            log.errorf(e, "[orphan-detection] Reconciliation pass failed; leaving the previous gauge values in place")
        }
    }

    private companion object {
        const val METRIC_SERVICE_TAG = "kyc"
        const val WORKFLOW_NAME = "kyc-orphaned-party-detection"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
