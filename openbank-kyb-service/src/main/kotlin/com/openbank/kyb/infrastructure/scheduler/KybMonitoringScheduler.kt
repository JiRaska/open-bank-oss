// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.scheduler

import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.usecase.MonitoringRegistrationService
import com.openbank.kyb.application.usecase.MonitoringRunOutcome
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.libs.observability.DomainMetrics
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * The scheduled run of ongoing-monitoring registration (ADR-0376, kyb side): observe the store,
 * then let [MonitoringRegistrationService] backfill, re-assert and send.
 *
 * A `suspend` function: a plain `@Scheduled` method carries no Vert.x context and its first
 * reactive call would abort the run having done nothing (rules.yaml: scheduled_methods).
 *
 * The store is observed on every run, switched on or off: an approved case under a monitoring
 * review needs its alert whatever the registration switch says. The workflow-liveness heartbeat
 * (ADR-0237) is registered only when registration is enabled — a heartbeat owned by a job that is
 * switched off never records a success and would page every environment that has not turned the
 * feature on — and it records a success only for a run that completed.
 *
 * `@Startup`: the heartbeat must exist from boot, not from the first tick of a lazy bean.
 */
@Startup
@ApplicationScoped
class KybMonitoringScheduler(
    private val service: MonitoringRegistrationService,
    private val store: MonitoringRegistrationStore,
    private val settings: MonitoringSettings,
    private val monitoringMetrics: KybMonitoringMetricsPort,
    private val clock: Clock,
    metrics: DomainMetrics,
) {
    private val liveness = if (settings.enabled) metrics.registerWorkflowLiveness(WORKFLOW, TICK) else null

    @Scheduled(
        every = "\${openbank.kyb.monitoring.relay.every:30s}",
        delayed = "\${openbank.kyb.monitoring.relay.initial-delay:20s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = WORKFLOW,
    )
    // TooGenericExceptionCaught: a failed run must be counted as failed and must not escape into
    // the scheduler, which would log it and carry on with nothing recording that the run failed.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run() {
        val outcome = try {
            monitoringMetrics.observed(store.stats(Instant.now(clock)))
            service.run().also {
                // What the run just did is part of what an operator should read next.
                if (it == MonitoringRunOutcome.COMPLETED) monitoringMetrics.observed(store.stats(Instant.now(clock)))
            }.name.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            Log.errorf(e, "kyb ongoing-monitoring registration run failed")
            OUTCOME_FAILED
        }
        monitoringMetrics.runFinished(outcome)
        if (outcome == OUTCOME_COMPLETED) liveness?.recordSuccess()
    }

    private companion object {
        const val WORKFLOW = "kyb-monitoring-registration"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_COMPLETED = "completed"
        val TICK: Duration = Duration.ofSeconds(30)
    }
}
