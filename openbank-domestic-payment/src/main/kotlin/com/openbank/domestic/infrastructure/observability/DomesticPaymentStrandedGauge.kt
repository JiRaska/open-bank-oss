// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPaymentStatus
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes how long payments have been sitting in a **non-terminal** state (#3273).
 *
 *  - `openbank_domestic_payments_non_terminal{service="domestic",status="..."}` — how many
 *    payments are in that state right now.
 *  - `openbank_domestic_payments_non_terminal_oldest_age_seconds{service="domestic",status="..."}`
 *    — age of the oldest one, or `0` when there are none.
 *
 * ### Why an age gauge and not another error signal
 *
 * domestic-payment was already covered by `PaymentServiceDown`, `HighErrorRate`, `HighLatencyP99`
 * and both `PaymentSLO*Burn` rules in `prometheus-rules-tier1.yaml`, and all of them were green
 * while seven payments sat in `RECEIVED` — the oldest for six weeks. They were green *correctly*: a
 * payment held fail-closed answered **2xx**, emitted no error span, took normal latency, and its pod
 * was healthy. Every existing rule measures whether the service **answers**; none measures whether
 * the work it accepted **progresses**. Nothing else in this service can express that, because a
 * stranded payment is indistinguishable from a healthy one at every layer except its age.
 *
 * Terminal states ([DomesticPaymentStatus.SETTLED], `REJECTED`, `CANCELLED`, `RETURNED`) are not
 * published: their age only grows and would alert forever.
 *
 * Per-status series rather than one aggregate, because the thresholds genuinely differ —
 * `SENT_TO_CLEARING` legitimately waits for a clearing cycle, while `RECEIVED` should never be more
 * than minutes old. One number for both could only be set to the loosest of them, which is the same
 * as not alerting on `RECEIVED` at all.
 *
 * Mirrors [com.openbank.domestic.infrastructure.outbox.DomesticPaymentOutboxBacklogGauge]:
 * Micrometer samples a gauge supplier synchronously on the Prometheus scrape (worker) thread, but
 * these come from reactive queries — so a scheduled `suspend` tick refreshes cached [AtomicLong]s on
 * the right context and the suppliers read those caches cheaply and lock-free.
 *
 * Service-local `MeterRegistry` (null-safe via [Instance], exactly like `ComplaintDeadlineGauge`):
 * this is a domestic-payment concern, so putting it in the shared libs `DomainMetrics` facade would
 * force a fleet-wide rebuild for one service's signal.
 */
@Startup
@ApplicationScoped
class DomesticPaymentStrandedGauge(
    private val paymentRepository: DomesticPaymentRepository,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. in slim test slices). Without an explicit @Inject constructor, ArC sees two
    // constructors, registers no bean, and the @Startup hook silently never runs.
    @Inject
    constructor(
        paymentRepository: DomesticPaymentRepository,
        registryInstance: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
    ) : this(
        paymentRepository,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        clock,
        domainMetrics,
    )

    private val counts: Map<DomesticPaymentStatus, AtomicLong> =
        NON_TERMINAL.associateWith { AtomicLong(0) }
    private val oldestAgeSeconds: Map<DomesticPaymentStatus, AtomicLong> =
        NON_TERMINAL.associateWith { AtomicLong(0) }
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        NON_TERMINAL.forEach { status ->
            gauge(r, "openbank.domestic.payments.non_terminal", status, counts.getValue(status))
            gauge(
                r,
                "openbank.domestic.payments.non_terminal.oldest.age.seconds",
                status,
                oldestAgeSeconds.getValue(status),
            )
        }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofSeconds(REFRESH_INTERVAL_SECONDS))
    }

    private fun gauge(r: MeterRegistry, name: String, status: DomesticPaymentStatus, holder: AtomicLong) {
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
            counts.getValue(status).set(paymentRepository.countByStatus(status))
            // Absent oldest row means the state is empty — report 0, not a stale previous age.
            val oldest = paymentRepository.oldestCreatedAt(status)
            oldestAgeSeconds.getValue(status).set(
                oldest?.let { maxOf(0L, Duration.between(it, now).seconds) } ?: 0L,
            )
        }
        liveness?.recordSuccess()
    }

    companion object {
        private const val SERVICE = "domestic"
        private const val WORKFLOW_NAME = "domestic-payment-stranded-gauge"
        private const val REFRESH_INTERVAL_SECONDS = 30L

        /**
         * States a payment must move out of. A payment in any of these has been accepted and is
         * still owed an outcome, so age is meaningful; in a terminal state it is not.
         */
        val NON_TERMINAL: List<DomesticPaymentStatus> = listOf(
            DomesticPaymentStatus.RECEIVED,
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
        )
    }
}
