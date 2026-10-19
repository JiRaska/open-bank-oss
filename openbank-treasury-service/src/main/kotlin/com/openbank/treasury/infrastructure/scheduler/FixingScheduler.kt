// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.usecase.FixingService
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration

/**
 * ADR-0344 D5: the daily fixing run for the bank's accounting day. Re-runs are harmless (a fixed
 * flow is no longer due), so a run that found an index blocked fixes it on a later pass once a
 * good observation is approved. Metric `openbank.treasury.fixings{outcome}`; the FIXINGS
 * day-completion step is recorded by the run itself.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context, and `runBlocking` around
 * reactive Panache throws HR000068 (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class FixingScheduler(
    private val fixings: FixingService,
    private val domainMetrics: DomainMetrics,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.fixing.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(FixingScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "{openbank.treasury.fixing.cron:0 15 * * * ?}",
        identity = "treasury-fixing-run",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun run() {
        if (!enabled) return
        val today = AccountingClock.bank(clock).today()
        runCatching { fixings.run(today) }
            .onSuccess { r ->
                meterRegistry.counter(METRIC, "outcome", "fixed").increment(r.fixedFlows.toDouble())
                meterRegistry.counter(METRIC, "outcome", "blocked").increment(r.blocked.size.toDouble())
                r.failures.forEach { log.error("fixing run could not fix an index", it) }
                r.blocked.forEach { log.warnf("fixing %s@%s BLOCKED: %s", it.index, it.fixingDate, it.blockReason) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
            }
            .onFailure { log.error("fixing run failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-fixing-run"
        const val METRIC = "openbank.treasury.fixings"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
