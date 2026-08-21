// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseTrigger
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Drives the scheduled monthly **period-close** cadence (ADR-0035 §F.1, ADR-0069 D3). The actual
 * work — self-healing catch-up enumeration, fail-closed reconciliation, run-outcome persistence and
 * `period.close_failed` emission — lives in [RunCloseUseCase] (CloseOrchestrator). This adapter only
 * wires the cron trigger to it.
 *
 * The cadence stays **disabled by default** ([enabled]); flip
 * `openbank.statement.scheduled-close.enabled=true` once the registry has back-filled and the
 * close-run telemetry/alerts are in place. The on-demand `POST /{accountId}/close` endpoint and the
 * operator `POST /api/v1/statements/close-runs` retry remain available regardless.
 */
@ApplicationScoped
class PeriodCloseScheduler(
    private val runClose: RunCloseUseCase,
    @ConfigProperty(name = "openbank.statement.scheduled-close.enabled", defaultValue = "false")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(PeriodCloseScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /** Register only when this opt-in scheduler is enabled, avoiding false stale alerts by default. */
    fun registerLiveness() {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onStart(@Observes event: StartupEvent) = registerLiveness()

    // Europe/Prague is explicit, not incidental (#1302): an unset @Scheduled timeZone means
    // JVM-default, so the close fires on the pod's zone, not the bank's accounting day —
    // the third clock regime from the closing audit. Prague matches ledger's BANK_TIME, so
    // the month-end close runs on the same day the ledger closed.
    @Scheduled(
        cron = "{openbank.statement.close-cron}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun monthlyClose() {
        if (!enabled) {
            log.debug("Scheduled period-close disabled; use POST /{accountId}/close or the operator retry")
            return
        }
        runClose.runClose(CloseTrigger.SCHEDULED).awaitSuspending()
        // COMPLETED_WITH_FAILURES is still a completed orchestration; StatementCloseFailures covers
        // individual pocket failures. Liveness must distinguish a completed control run from an
        // invocation that never reached the reactive use case.
        liveness?.recordSuccess()
    }

    private companion object {
        const val WORKFLOW_NAME = "statement-period-close"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(31)
    }
}
