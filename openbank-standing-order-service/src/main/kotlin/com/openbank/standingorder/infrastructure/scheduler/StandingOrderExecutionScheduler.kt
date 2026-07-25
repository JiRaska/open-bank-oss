// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Daily execution sweep — finds all ACTIVE standing orders due on or before today and emits
 * a [standing-order.due.v1] outbox event for each, atomically advancing nextExecutionDate.
 *
 * The scheduler runs at 03:00 UTC (configurable via openbank.scheduler.execution-cron) with
 * SKIP concurrency so a slow sweep does not overlap with the next day's run.
 *
 * [com.openbank.standingorder.infrastructure.kafka.StandingOrderDueConsumer] self-consumes the
 * `standing-order.due.v1` events this sweep emits and initiates the real payment (#889) — see
 * that class for the per-rail dispatch. Idempotency is keyed on
 * "so-exec-{orderId}-{executionDate}" so redeliveries are safe.
 *
 * Carries a [WorkflowLivenessRecorder] (ADR-0160 mechanism 3) so a silently-broken or
 * silently-stopped sweep pages instead of leaving no trace — the same failure mode that let
 * balance-service's reconciliation run zero rows for 41 days unnoticed (issue #855) before this
 * primitive existed to generalize its watchdog to any scheduled job.
 */
@ApplicationScoped
class StandingOrderExecutionScheduler {

    @Inject
    lateinit var standingOrderUseCase: StandingOrderUseCase

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "openbank.scheduler.execution-enabled", defaultValue = "true")
    var enabled: Boolean = true

    private val log = Logger.getLogger(StandingOrderExecutionScheduler::class.java)
    private lateinit var liveness: WorkflowLivenessRecorder

    // Registered once at startup (CDI beans are singletons here), not per-run — matches
    // DomainMetrics.registerOutboxBacklog's own "call once" contract.
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(
        cron = "{openbank.scheduler.execution-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // MUST be a suspend fun, NOT `runBlocking { }`: Quarkus dispatches a suspend @Scheduled method
    // on a Vert.x context, whereas runBlocking runs the coroutine on the bare scheduler
    // executor-thread, which carries no Vert.x context — the first Hibernate-Reactive call
    // (findDueForExecution) then throws HR000068 and the whole sweep aborts with zero outbox rows
    // (issue #2148). The sibling StandingOrderOutboxDispatcher.dispatch() is already a suspend fun for
    // exactly this reason. Covered by StandingOrderExecutionSweepIT against a real DB.
    suspend fun sweep() {
        if (!enabled) {
            log.debug("[execution-scheduler] Disabled — skipping sweep")
            return
        }
        val today = LocalDate.now(clock)
        log.infof("[execution-scheduler] Starting daily execution sweep for %s", today)
        val count = standingOrderUseCase.executeOrders(today)
        log.infof("[execution-scheduler] Daily execution sweep done: %d orders scheduled", count)
        // Recorded on every successful sweep, including a legitimate "0 orders due today" — the
        // watchdog tracks that the SWEEP ran, not that it found work; a day with genuinely no due
        // orders must not read as a missed control.
        liveness.recordSuccess()
    }

    private companion object {
        const val WORKFLOW_NAME = "standing-order-execution"
    }
}
