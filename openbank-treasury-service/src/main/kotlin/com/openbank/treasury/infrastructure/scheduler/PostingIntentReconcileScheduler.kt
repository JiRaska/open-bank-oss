// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
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
 * #13883: completes the posting intents settle, mature and reverse left OPEN — a journal the ledger
 * may hold that treasury has not applied (the process died between the ledger's answer and the local
 * commit, or the commit failed). Each is re-run under its original journal key, so the ledger answers
 * with the original journal and nothing posts twice. Only intents older than
 * `openbank.treasury.posting-intent.grace` are taken, so a command still in flight is not raced.
 * A failing intent stays open (and keeps holding its day's close report) with its cause recorded.
 * Metric `openbank.treasury.posting_intents{outcome}`.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context, and `runBlocking` around
 * reactive Panache throws HR000068 (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class PostingIntentReconcileScheduler(
    private val deals: TreasuryDealUseCase,
    private val domainMetrics: DomainMetrics,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.posting-intent.reconcile-enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.treasury.posting-intent.grace", defaultValue = "PT2M")
    private val grace: Duration,
) {
    private val log = Logger.getLogger(PostingIntentReconcileScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "{openbank.treasury.posting-intent.reconcile-cron:0 */5 * * * ?}",
        identity = "treasury-posting-intent-reconcile",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { deals.reconcilePostingIntents(clock.instant().minus(grace)) }
            .onSuccess { r ->
                meterRegistry.counter(METRIC, "outcome", "completed").increment(r.completed.toDouble())
                meterRegistry.counter(METRIC, "outcome", "failed").increment(r.failures.size.toDouble())
                r.failures.forEach { log.error("posting intent could not be completed; it stays open", it) }
                // Liveness = every open intent was completed, not merely "the tick ran".
                if (r.failures.isEmpty()) liveness?.recordSuccess()
            }
            .onFailure { log.error("posting intent reconciliation failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-posting-intent-reconcile"
        const val METRIC = "openbank.treasury.posting_intents"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
