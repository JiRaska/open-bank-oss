// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.usecase.RevaluationService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-0344 D7/D8: the daily revaluation of the bank's accounting day, off the risk engine's
 * TIED_OUT snapshot of that date. Re-runs are harmless (idempotent per deal and date); a deal the
 * engine could not value is a gap that marks the REVALUATION day step FAILED with its reason (the
 * run records the step itself). Metric `openbank.treasury.revaluations{outcome}`.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context, and `runBlocking` around
 * reactive Panache throws HR000068 (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class RevaluationScheduler(
    private val revaluation: RevaluationService,
    private val domainMetrics: DomainMetrics,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.revaluation.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(RevaluationScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    // NaN until the first run has measured them: a cold pod neither fires nor clears the alert.
    private val unpostedCount = AtomicReference(Double.NaN)
    private val oldestUnpostedAge = AtomicReference(Double.NaN)

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!enabled) return
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        Gauge.builder(UNPOSTED_METRIC) { unpostedCount.get() }
            .description("Revaluations claimed but not yet posted to the ledger (re-posted first by the next run)")
            .register(meterRegistry)
        Gauge.builder("$UNPOSTED_METRIC.oldest.age.seconds") { oldestUnpostedAge.get() }
            .description("Age of the oldest unposted revaluation claim; 0 when none")
            .register(meterRegistry)
    }

    private suspend fun measureUnposted() {
        runCatching { revaluation.unposted() }.onSuccess { u ->
            unpostedCount.set(u.count.toDouble())
            oldestUnpostedAge.set(
                u.oldestClaimedAt?.let { Duration.between(it, clock.instant()).seconds.coerceAtLeast(0).toDouble() }
                    ?: 0.0,
            )
        }.onFailure { log.error("could not measure unposted revaluations", it) }
    }

    @Scheduled(
        cron = "{openbank.treasury.revaluation.cron:0 30 20 * * ?}",
        identity = "treasury-revaluation-run",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun run() {
        if (!enabled) return
        val today = AccountingClock.bank(clock).today()
        runCatching { revaluation.revalue(today) }
            .onSuccess { r ->
                meterRegistry.counter(METRIC, "outcome", "revalued").increment(r.revalued.size.toDouble())
                meterRegistry.counter(METRIC, "outcome", "gap").increment(r.gaps.size.toDouble())
                meterRegistry.counter(METRIC, "outcome", "failed").increment(r.failures.size.toDouble())
                r.gaps.forEach { log.warnf("revaluation of %s on %s is a DATA GAP: %s", it.dealId, today, it.reason) }
                r.failures.forEach { log.error("revaluation could not revalue a deal", it) }
                if (r.failures.isEmpty() && r.gaps.isEmpty()) liveness?.recordSuccess()
            }
            .onFailure { log.error("revaluation run failed", it) }
        measureUnposted()
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-revaluation-run"
        const val METRIC = "openbank.treasury.revaluations"
        const val UNPOSTED_METRIC = "treasury.revaluation.unposted"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
