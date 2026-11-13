// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.MonitoringWorkRepository
import com.openbank.sanctions.application.port.out.SanctionsMonitoringMetricsPort
import com.openbank.sanctions.application.usecase.SanctionsMonitoringService
import com.openbank.sanctions.application.usecase.SanctionsMonitoringSettings
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/**
 * The two scheduled paths of ongoing monitoring (ADR-0376): the delta over changed list entries
 * and the bounded sweep over the registry.
 *
 * Both are `suspend` functions: a plain `@Scheduled` method carries no Vert.x context and its
 * first reactive call would abort the run (rules.yaml: scheduled_methods).
 *
 * The workflow-liveness heartbeats (ADR-0237) are registered only when monitoring is enabled. A
 * heartbeat registered by a disabled job would never record a success, and `WorkflowLivenessStale`
 * would fire in every environment that has not switched the feature on. When it is enabled, a
 * success is recorded only for a run that completed its work: a failed run and a run that could
 * only request a re-sweep both leave the heartbeat to age.
 *
 * `@Startup`: the heartbeat must exist from boot, not from the first tick of a lazy bean.
 */
@Startup
@ApplicationScoped
class SanctionsMonitoringScheduler(
    private val monitoring: SanctionsMonitoringService,
    private val subjects: MonitoredSubjectRepository,
    private val work: MonitoringWorkRepository,
    private val settings: SanctionsMonitoringSettings,
    private val monitoringMetrics: SanctionsMonitoringMetricsPort,
    metrics: DomainMetrics,
) {
    private val deltaLiveness =
        if (settings.enabled) metrics.registerWorkflowLiveness(DELTA_WORKFLOW, TICK) else null
    private val sweepLiveness =
        if (settings.enabled) metrics.registerWorkflowLiveness(SWEEP_WORKFLOW, TICK) else null

    @Scheduled(
        every = "60s",
        delayed = "45s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = DELTA_WORKFLOW,
    )
    suspend fun delta() {
        if (run(DELTA_PATH) { monitoring.runDelta() } == MonitoringRunOutcome.COMPLETED) deltaLiveness?.recordSuccess()
    }

    @Scheduled(
        every = "60s",
        delayed = "75s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = SWEEP_WORKFLOW,
    )
    suspend fun sweep() {
        val outcome = run(SWEEP_PATH) {
            monitoring.runSweep().also {
                if (it != MonitoringRunOutcome.DISABLED) {
                    monitoringMetrics.registryObserved(subjects.stats(), work.pendingCount())
                }
            }
        }
        if (outcome == MonitoringRunOutcome.COMPLETED) sweepLiveness?.recordSuccess()
    }

    // TooGenericExceptionCaught: a failed tick must be counted as FAILED and must not escape into
    // the scheduler, which would log it and carry on with nothing recording that the run failed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(path: String, block: suspend () -> MonitoringRunOutcome): MonitoringRunOutcome {
        val outcome = try {
            block()
        } catch (e: Exception) {
            Log.errorf(e, "Sanctions monitoring %s run failed", path)
            MonitoringRunOutcome.FAILED
        }
        monitoringMetrics.runFinished(path, outcome)
        return outcome
    }

    private companion object {
        const val DELTA_WORKFLOW = "sanctions-monitoring-delta"
        const val SWEEP_WORKFLOW = "sanctions-monitoring-sweep"
        const val DELTA_PATH = "delta"
        const val SWEEP_PATH = "sweep"
        val TICK: Duration = Duration.ofSeconds(60)
    }
}
