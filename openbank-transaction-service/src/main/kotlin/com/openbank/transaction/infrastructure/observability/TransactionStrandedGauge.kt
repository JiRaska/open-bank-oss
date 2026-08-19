// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.transaction.application.port.out.StrandedSagaQueryPort
import com.openbank.transaction.domain.model.TransactionStatus
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes how long payment sagas have been sitting in a **non-terminal** state (issue #5733).
 *
 *  - `openbank_transactions_non_terminal{service="transaction",status="..."}` — how many
 *    transactions are in that state right now.
 *  - `openbank_transactions_non_terminal_oldest_age_seconds{service="transaction",status="..."}`
 *    — age of the oldest one, or `0` when there are none.
 *
 * ### Why this exists
 *
 * `TransactionSagaStuck` — `severity: critical`, money-path, whose whole job is to page a human
 * when a saga wedges — was written against `openbank_transaction_sagas_stuck_total`, a metric
 * **nothing has ever emitted**. The rule loaded, `promtool` accepted it, Alertmanager routed it,
 * and Prometheus reported it `inactive` forever, which is exactly what a correctly-quiet alert
 * also looks like. It could not fire, and nothing anywhere disagreed (#5733).
 *
 * This is the observable the alert always needed, and it is deliberately the same shape as
 * [com.openbank.domestic.infrastructure.observability.DomesticPaymentStrandedGauge] in
 * domestic-payment (#3273), which was built for the identical failure: a payment held
 * fail-closed answers 2xx, emits no error span, takes normal latency and sits on a healthy pod,
 * so every rule that measures whether the service *answers* stays green while work it accepted
 * stops *progressing*. Age is the only layer at which the two differ.
 *
 * Terminal states ([TransactionStatus.COMPLETED], `FAILED`, `REVERSED`) are not published: their
 * age only grows and would alert forever.
 *
 * Per-status series rather than one aggregate, because the thresholds differ — `PROCESSING` is
 * mid-saga and legitimately takes seconds to low minutes, while `PENDING` means the saga was
 * accepted and has not started. One number could only carry the loosest of the two.
 *
 * Micrometer samples a gauge supplier synchronously on the Prometheus scrape (worker) thread, but
 * these come from reactive queries — so a scheduled `suspend` tick refreshes cached [AtomicLong]s
 * on the right context and the suppliers read those caches cheaply and lock-free. That is also why
 * the scheduled method is `suspend` and not `runBlocking`: a plain `@Scheduled` carries no Vert.x
 * context and a blocking bridge around reactive Panache throws HR000068, aborting the job silently
 * (#2148/#2187, enforced by check-no-runblocking-in-scheduled.py).
 *
 * The refresh registers its own liveness (ADR-0160 mechanism 3), so a gauge that silently stops
 * updating — the failure that would make this control quietly useless — is itself alertable via
 * `WorkflowLivenessStale`, rather than freezing at its last value and reading as healthy.
 */
@Startup
@ApplicationScoped
class TransactionStrandedGauge(
    private val strandedSagaQuery: StrandedSagaQueryPort,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. in slim test slices). Without an explicit @Inject constructor, ArC sees two
    // constructors, registers no bean, and the @Startup hook silently never runs.
    @Inject
    constructor(
        strandedSagaQuery: StrandedSagaQueryPort,
        registryInstance: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
    ) : this(
        strandedSagaQuery,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        clock,
        domainMetrics,
    )

    private val counts: Map<TransactionStatus, AtomicLong> = NON_TERMINAL.associateWith { AtomicLong(0) }
    private val oldestAgeSeconds: Map<TransactionStatus, AtomicLong> = NON_TERMINAL.associateWith { AtomicLong(0) }
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        NON_TERMINAL.forEach { status ->
            gauge(r, "openbank.transactions.non_terminal", status, counts.getValue(status))
            gauge(
                r,
                "openbank.transactions.non_terminal.oldest.age.seconds",
                status,
                oldestAgeSeconds.getValue(status),
            )
        }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofSeconds(REFRESH_INTERVAL_SECONDS))
    }

    private fun gauge(r: MeterRegistry, name: String, status: TransactionStatus, holder: AtomicLong) {
        Gauge.builder(name, holder) { it.get().toDouble() }
            .tag("service", SERVICE)
            .tag("status", status.name)
            .strongReference(true)
            .register(r)
    }

    @Scheduled(
        every = "30s",
        delayed = "15s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        val now = Instant.now(clock)
        NON_TERMINAL.forEach { status ->
            counts.getValue(status).set(strandedSagaQuery.countByStatus(status))
            // Absent oldest row means the state is empty — report 0, not a stale previous age.
            val oldest = strandedSagaQuery.oldestInitiatedAt(status)
            oldestAgeSeconds.getValue(status).set(
                oldest?.let { maxOf(0L, Duration.between(it, now).seconds) } ?: 0L,
            )
        }
        liveness?.recordSuccess()
    }

    companion object {
        private const val SERVICE = "transaction"
        private const val WORKFLOW_NAME = "transaction-stranded-gauge"
        private const val REFRESH_INTERVAL_SECONDS = 30L

        /**
         * States a saga must move out of. A transaction in either has been accepted and is still
         * owed an outcome, so age is meaningful; in a terminal state it is not.
         */
        val NON_TERMINAL: List<TransactionStatus> = listOf(
            TransactionStatus.PENDING,
            TransactionStatus.PROCESSING,
        )
    }
}
