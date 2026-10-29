// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.PostingIntentPort
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
import java.util.concurrent.atomic.AtomicLong

/**
 * #13883: completes the posting intents settle, mature and reverse left OPEN — a journal the ledger
 * may hold that treasury has not applied (the process died between the ledger's answer and the local
 * commit, or the commit failed). Each is re-run under its original journal key, so the ledger answers
 * with the original journal and nothing posts twice. Only intents older than
 * `openbank.treasury.posting-intent.grace` are taken, so a command still in flight is not raced, and
 * each is claimed (`FOR UPDATE SKIP LOCKED` + lease) so two pods never resume the same one. An intent
 * older than `max-resume-age` or from an earlier business day is escalated, not resumed.
 *
 * Signals: `openbank.treasury.posting_intents{outcome=completed|failed|escalated}` per pass, and the
 * gauges `openbank.treasury.posting_intents.open` / `.oldest_age_seconds` (-1 = could not be read),
 * which `prometheus-rules-treasury-posting-intents.yaml` alerts on. Liveness means "the tick ran";
 * a stuck intent is the gauges' job, not the heartbeat's.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context, and `runBlocking` around
 * reactive Panache throws HR000068 (check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class PostingIntentReconcileScheduler(
    private val deals: TreasuryDealUseCase,
    private val intents: PostingIntentPort,
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
    private val open = AtomicLong(0)
    private val oldestAgeSeconds = AtomicLong(0)

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        Gauge.builder(OPEN_GAUGE, open) { it.get().toDouble() }
            .description("OPEN treasury posting intents (-1: unreadable)").register(meterRegistry)
        Gauge.builder(OLDEST_GAUGE, oldestAgeSeconds) { it.get().toDouble() }
            .description("Age of the oldest OPEN treasury posting intent (-1: unreadable)").register(meterRegistry)
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "{openbank.treasury.posting-intent.reconcile-cron:0 */5 * * * ?}",
        identity = "treasury-posting-intent-reconcile",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun run() {
        if (!enabled) {
            observe() // a switched-off reconciler must not make stuck intents invisible
            return
        }
        runCatching { deals.reconcilePostingIntents(clock.instant().minus(grace)) }
            .onSuccess { r ->
                meterRegistry.counter(METRIC, "outcome", "completed").increment(r.completed.toDouble())
                meterRegistry.counter(METRIC, "outcome", "failed").increment(r.failures.size.toDouble())
                meterRegistry.counter(METRIC, "outcome", "escalated").increment(r.escalated.toDouble())
                r.failures.forEach { log.error("posting intent could not be completed; it stays open", it) }
                if (r.escalated > 0) {
                    log.warnf("%d posting intent(s) escalated to an operator; they stay open", r.escalated)
                }
                liveness?.recordSuccess()
            }
            .onFailure { log.error("posting intent reconciliation failed", it) }
        observe()
    }

    /** Refreshes the gauges; visible for tests. */
    suspend fun observe() {
        runCatching { intents.summary() }
            .onSuccess { s ->
                open.set(s.count.toLong())
                oldestAgeSeconds.set(
                    s.oldestCreatedAt?.let { Duration.between(it, clock.instant()).seconds.coerceAtLeast(0) } ?: 0,
                )
            }
            .onFailure {
                log.error("posting intent evidence unavailable", it)
                open.set(-1)
                oldestAgeSeconds.set(-1)
            }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-posting-intent-reconcile"
        const val METRIC = "openbank.treasury.posting_intents"
        const val OPEN_GAUGE = "openbank.treasury.posting_intents.open"
        const val OLDEST_GAUGE = "openbank.treasury.posting_intents.oldest_age_seconds"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
