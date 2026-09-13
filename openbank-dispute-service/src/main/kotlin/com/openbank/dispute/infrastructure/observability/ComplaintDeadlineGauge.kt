// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.dispute.infrastructure.observability

import com.openbank.dispute.application.port.out.ComplaintRepository
import com.openbank.dispute.application.usecase.isBreached
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the complaints **statutory-deadline** state as Micrometer gauges (ADR-0085 §2). The
 * whole point of the ADR is that a breached PSD2 Art. 101 deadline is an *operational incident*, not
 * a backlog item — so it must be visible to ops *before* it breaches:
 *
 *  - `openbank_complaints_open{service="dispute"}`      — open (RECEIVED) complaints right now.
 *  - `openbank_complaints_due_soon{service="dispute"}`  — open and due within the next
 *    [DUE_SOON_BUSINESS_DAYS] business days (the *pre*-breach warning the ADR asks for).
 *  - `openbank_complaints_due_breach{service="dispute"}`— open AND already past the statutory due
 *    date. The ADR sketches this as a `_total` counter; it is realised as a **gauge of the current
 *    breached count** instead — that is the alertable "is any deadline breached right now?" signal
 *    and avoids fragile cross-restart breach-transition bookkeeping. A cumulative counter can be
 *    layered on later from the `complaint.*` outbox stream if trend analysis needs it.
 *
 * Mirrors [com.openbank.dispute.infrastructure.outbox.DisputeOutboxBacklogGauge]: Micrometer samples
 * a gauge supplier synchronously on the Prometheus scrape (worker) thread, but the counts come from a
 * reactive query — so a scheduled `suspend` tick refreshes cached [AtomicLong]s on the right context
 * and the suppliers read those caches cheaply and lock-free.
 *
 * The breach predicate reuses the domain [isBreached] helper (the SAME definition the API serves), so
 * a metric can never disagree with what an operator sees on a complaint. Counts are derived in-memory
 * from the open set rather than via dedicated COUNT queries: complaint volume is small, it avoids
 * three round-trips per tick, and — critically — it adds no new persistence code to test against a DB.
 *
 * Service-local `MeterRegistry` (null-safe via [Instance], exactly like libs `DomainMetrics`): these
 * meters are dispute-specific, so adding them to the shared libs `DomainMetrics` facade would force a
 * fleet-wide rebuild for a one-service concern (the reason ADR-0085 §2 was deferred). Registering
 * directly on the in-cluster Prometheus registry keeps the change inside this service.
 */
@Startup
@ApplicationScoped
class ComplaintDeadlineGauge(
    private val complaintRepo: ComplaintRepository,
    private val registry: MeterRegistry?,
    private val clock: Clock,
) {
    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. in slim test slices) and Clock is not a CDI bean. Without an explicit @Inject
    // ctor, ArC sees two constructors, registers no bean, and the @Startup hook silently never runs.
    @Inject
    constructor(complaintRepo: ComplaintRepository, registryInstance: Instance<MeterRegistry>) : this(
        complaintRepo,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        Clock.system(BANK_TIME),
    )

    private val calendar: BusinessCalendar = BusinessCalendar.forCurrency("CZK")

    private val open = AtomicLong(0)
    private val dueSoon = AtomicLong(0)
    private val breached = AtomicLong(0)

    @Inject
    lateinit var domainMetrics: DomainMetrics
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        val r = registry ?: return
        gauge(r, "openbank.complaints.open", open)
        gauge(r, "openbank.complaints.due_soon", dueSoon)
        gauge(r, "openbank.complaints.due_breach", breached)
    }

    private fun gauge(r: MeterRegistry, name: String, holder: AtomicLong) {
        Gauge.builder(name, holder) { it.get().toDouble() }
            .tag("service", SERVICE)
            .strongReference(true)
            .register(r)
    }

    @Scheduled(
        every = "30s",
        delayed = "15s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        val today = LocalDate.now(clock)
        val soonCutoff = calendar.addBusinessDays(today, DUE_SOON_BUSINESS_DAYS)
        val openComplaints = complaintRepo.findByStatus(ComplaintStatus.RECEIVED).awaitSuspending()

        open.set(openComplaints.size.toLong())
        breached.set(openComplaints.count { isBreached(it, today) }.toLong())
        // Approaching: open, not yet breached, and due on/before the warning cutoff.
        dueSoon.set(
            openComplaints.count { !isBreached(it, today) && !it.dueDate.isAfter(soonCutoff) }.toLong(),
        )
        liveness?.recordSuccess()
    }

    companion object {
        private const val SERVICE = "dispute"
        private const val WORKFLOW_NAME = "complaint-deadline-gauge-refresh"
        private val EXPECTED_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Warn this many business days before the statutory deadline (PSD2 Art. 101 is 15 BD). */
        const val DUE_SOON_BUSINESS_DAYS = 3
        private val BANK_TIME: ZoneId = ZoneId.of("Europe/Prague")
    }
}
