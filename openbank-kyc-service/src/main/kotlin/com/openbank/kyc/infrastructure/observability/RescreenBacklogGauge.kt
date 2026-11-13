// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.port.out.ScreeningTriggerBacklog
import com.openbank.kyc.application.port.out.ScreeningTriggerRepository
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the ADR-0256 re-screening backlog — `kyc_screening_triggers` rows that have not yet
 * become an opened case — read from the DATABASE, so every pod reports the same truth and a
 * restart loses nothing (issue #15961).
 *
 *  - `openbank_kyc_rescreen_backlog_pending{service="kyc"}` — rows in PENDING or OPEN_FAILED
 *    (first attempt in flight, retrying, or parked after the attempt budget).
 *  - `openbank_kyc_rescreen_backlog_oldest_age_seconds{service="kyc"}` — `now() - created_at` of
 *    the oldest such row, measured by the database clock from ENQUEUE time. `0` only when the
 *    backlog is empty. Never derived from `next_attempt_at`: every retry pushes that forward, so a
 *    row failing forever would look permanently young.
 *  - `openbank_kyc_rescreen_loop_disabled_with_pending{service="kyc"}` — `1` when the loop is
 *    switched off (`screening-triggers.enabled` or `screening-triggers.retry-enabled` false) AND
 *    the backlog is non-empty, else `0`. A separate series on purpose: an empty queue and a queue
 *    nobody will drain must not be told apart by reading one gauge's value against config.
 *
 * ### The value at t=0 on a cold pod
 *
 * All three read **NaN** until the first successful sample — not 0, which would claim an empty,
 * healthy queue nobody measured, and not an `Instant.EPOCH`-derived age, which would read as
 * decades (#2239). Every comparison against NaN is false, so no rule can fire on a cold pod, and
 * "not measured" stays distinguishable from "measured empty". The first sample lands one interval
 * (1m) after start. A sampler that never succeeds is caught by `WorkflowLivenessStale` on the
 * `kyc-rescreen-backlog-sample` heartbeat, which is seeded at registration (its age at boot is
 * the pod's uptime, never decades).
 *
 * ### Failure semantics
 *
 * A failed sample (kyc-db down) keeps the previous values and records no liveness success —
 * resetting to NaN or 0 would clear a firing overdue alert exactly when the store is unreachable.
 * Staleness is the heartbeat's job.
 *
 * One aggregate query per minute (`ScreeningTriggerRepositoryImpl.BACKLOG`), an index range on
 * the leading `state` column of `ix_kyc_screening_triggers_retry`; OPENED history is never read.
 */
@Startup
@ApplicationScoped
class RescreenBacklogGauge(
    private val repository: ScreeningTriggerRepository,
    private val registry: MeterRegistry?,
    private val domainMetrics: DomainMetrics,
    private val loopEnabled: Boolean,
) {
    // Explicit @Inject constructor: MeterRegistry is optional (absent in slim test slices); with
    // two constructors and no @Inject ArC registers no bean (OrphanedPartyGauge precedent).
    @Inject
    constructor(
        repository: ScreeningTriggerRepository,
        registryInstance: Instance<MeterRegistry>,
        domainMetrics: DomainMetrics,
        @ConfigProperty(name = "openbank.kyc.screening-triggers.enabled", defaultValue = "false")
        triggersEnabled: Boolean,
        @ConfigProperty(name = "openbank.kyc.screening-triggers.retry-enabled", defaultValue = "true")
        retryEnabled: Boolean,
    ) : this(
        repository,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        domainMetrics,
        triggersEnabled && retryEnabled,
    )

    private val log = Logger.getLogger(RescreenBacklogGauge::class.java)

    /** Null until the first successful sample — the gauges read NaN, never a guessed value. */
    private val last = AtomicReference<ScreeningTriggerBacklog?>(null)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        gauge(r, METRIC_PENDING) { it.pending.toDouble() }
        gauge(r, METRIC_OLDEST_AGE) { it.oldestAgeSeconds.toDouble() }
        gauge(r, METRIC_DISABLED_WITH_PENDING) { b -> disabledWithPending(b) }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /** Current values as the gauges publish them — NaN before the first sample. */
    fun pending(): Double = last.get()?.pending?.toDouble() ?: Double.NaN

    fun oldestAgeSeconds(): Double = last.get()?.oldestAgeSeconds?.toDouble() ?: Double.NaN

    fun disabledWithPending(): Double = last.get()?.let { disabledWithPending(it) } ?: Double.NaN

    private fun disabledWithPending(b: ScreeningTriggerBacklog): Double =
        if (!loopEnabled && b.pending > 0) 1.0 else 0.0

    private fun gauge(r: MeterRegistry, name: String, value: (ScreeningTriggerBacklog) -> Double) {
        Gauge.builder(name, last) { ref -> ref.get()?.let(value) ?: Double.NaN }
            .tag("service", METRICS_SERVICE_TAG)
            .strongReference(true)
            .register(r)
    }

    /**
     * `suspend fun`: a plain @Scheduled body has no Vert.x context and the reactive query would
     * fail with HR000068 (#2148, #2187). Runs whether or not the loop is enabled — the disabled
     * state is precisely what this has to report.
     */
    // TooGenericExceptionCaught: a tick must never crash the scheduler; every fault keeps the
    // previous values and lets the heartbeat go stale.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        every = "{openbank.kyc.screening-triggers.backlog-sample-interval:1m}",
        identity = "kyc-rescreen-backlog-sample",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sample() {
        try {
            last.set(repository.backlog())
            liveness?.recordSuccess()
        } catch (e: Exception) {
            log.errorf(e, "[rescreen-backlog] sample failed; keeping the previous gauge values")
        }
    }

    companion object {
        const val METRIC_PENDING = "openbank.kyc.rescreen.backlog.pending"
        const val METRIC_OLDEST_AGE = "openbank.kyc.rescreen.backlog.oldest.age.seconds"
        const val METRIC_DISABLED_WITH_PENDING = "openbank.kyc.rescreen.loop.disabled.with.pending"
        private const val METRICS_SERVICE_TAG = "kyc"
        private const val WORKFLOW_NAME = "kyc-rescreen-backlog-sample"
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
