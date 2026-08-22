// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementStatus
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
 * Publishes how long settlements have been sitting in a **non-terminal** state (issue #5705).
 *
 *  - `openbank_settlement_non_terminal{service="settlement",status="..."}` — how many settlements
 *    are in that state right now.
 *  - `openbank_settlement_non_terminal_oldest_age_seconds{service="settlement",status="..."}` —
 *    age of the oldest one, or `0` when there are none.
 *
 * ### Why age, and not a throughput counter
 *
 * settlement-service is REST- and Temporal-driven: `POST /settlements` starts a workflow, and the
 * service has no `@Scheduled` sweep and no `@Incoming` consumer for settlement traffic. So "no
 * settlement completed in the last hour" is the NORMAL resting state, not a fault. A throughput
 * floor over such a flow is precisely the mistake #5733 deleted twice from this domain's rules
 * (`ClearingSettlementWindowMissed`, `SwiftMtMessageProcessingStalled`) — it asserts a cadence the
 * code does not have, so it is either permanently firing or permanently ignored.
 *
 * What settlement *does* have is a state machine that must make progress. A settlement stuck in
 * [SettlementStatus.DEBITED] is the money-path failure this service can produce: the payer has
 * been debited, the payee has not been credited, and the funds are in neither account. Nothing
 * else in the fleet can see it. `PaymentServiceDown` is green (the pod is healthy), the Pyrra
 * availability and latency SLOs are green (the originating request answered 2xx in milliseconds
 * and the workflow's later failure is not that span), and there is no error rate to raise because
 * a workflow that stopped advancing throws nothing on the request path. The ONLY observable that
 * separates a stranded settlement from a healthy one is its age — same shape, same reasoning, as
 * `DomesticPaymentStrandedGauge` in openbank-domestic-payment (#3273).
 *
 * ### Scope
 *
 * This is the PROGRESS half of #5705 — "is this settlement still moving". The OUTCOME half
 * ("what did settlements do": origination, per-step and terminal-state counters) is PR #5723's
 * `SettlementMetricsPort`, and is deliberately not duplicated here. The two answer different
 * questions and neither substitutes for the other: a counter cannot see one settlement that
 * stopped while traffic flows normally around it, and an age gauge cannot see a rejection rate.
 *
 * ### Which states are published
 *
 * Terminal states ([SettlementStatus.BOOKED], [SettlementStatus.REJECTED]) are not published:
 * their age only grows and would alert forever. Everything else is published, including all five
 * compensation outcomes — `SettlementWorkflowImpl` always calls `rejectSettlement` after
 * compensating, so a row parked in `REVERSED` / `CREDITED_REVERSED` / `LEDGER_REVERSED` /
 * `REVERSAL_FAILED` / `LEDGER_REVERSAL_UNSUPPORTED` means the unwinding ran (or refused) and the
 * record never reached its terminal state.
 *
 * The set is DERIVED from [SettlementStatus] rather than listed, because it was listed once and
 * went stale: #6037 split the compensation outcomes into their own values and `REVERSAL_FAILED` /
 * `LEDGER_REVERSAL_UNSUPPORTED` were left unpublished, so the two states that mean *the money did
 * not come back* were the only two the stranded-settlement rules could never see.
 *
 * ### t=0 on a cold pod
 *
 * Every series is created in [register] at startup and initialised to `0`. A freshly started pod
 * therefore publishes `0` for each status rather than an absent series, so a `> threshold` rule
 * cannot fire during or after a deploy, and `absent()` over these series means "the pod is not
 * exporting", not "no settlements". The gauges hold no seeded age at all, so there is no
 * `Instant.EPOCH` fourth state here — the boot reading and the healthy reading are the same
 * number, deliberately.
 *
 * ### Why the refresh loop carries its own liveness heartbeat
 *
 * The gauges are only as trustworthy as the tick that refreshes them: a frozen [refresh] leaves
 * every age stuck at its last value, which reads as healthy. [refresh] therefore records a
 * success against `DomainMetrics.registerWorkflowLiveness`, whose 30s cadence IS a real cadence,
 * so the fleet `WorkflowLivenessStale` rule (2x interval, `for: 15m`) covers the collector without
 * anyone asserting a cadence on settlement traffic itself. The age gauge behind that rule is
 * seeded at REGISTRATION, not `Instant.EPOCH`, so it does not fire 15 minutes after every deploy.
 *
 * Micrometer samples a gauge supplier synchronously on the Prometheus scrape (worker) thread while
 * these values come from reactive queries, so a scheduled `suspend` tick refreshes cached
 * [AtomicLong]s on the right context and the suppliers read those caches cheaply and lock-free. A
 * plain (non-`suspend`) `@Scheduled` method has no Vert.x context and would abort with `HR000068`
 * on the first reactive Panache call, silently.
 */
@Startup
@ApplicationScoped
class SettlementStrandedGauge(
    private val settlementRepository: SettlementRepository,
    private val registry: MeterRegistry?,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. in slim test slices). Without an explicit @Inject constructor, ArC sees two
    // constructors, registers no bean, and the @Startup hook silently never runs.
    @Inject
    constructor(
        settlementRepository: SettlementRepository,
        registryInstance: Instance<MeterRegistry>,
        clock: Clock,
        domainMetrics: DomainMetrics,
    ) : this(
        settlementRepository,
        if (registryInstance.isResolvable) registryInstance.get() else null,
        clock,
        domainMetrics,
    )

    private val counts: Map<SettlementStatus, AtomicLong> = NON_TERMINAL.associateWith { AtomicLong(0) }
    private val oldestAgeSeconds: Map<SettlementStatus, AtomicLong> = NON_TERMINAL.associateWith { AtomicLong(0) }
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        val r = registry ?: return
        NON_TERMINAL.forEach { status ->
            gauge(r, NON_TERMINAL_METRIC, status, counts.getValue(status))
            gauge(r, OLDEST_AGE_METRIC, status, oldestAgeSeconds.getValue(status))
        }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofSeconds(REFRESH_INTERVAL_SECONDS))
    }

    private fun gauge(r: MeterRegistry, name: String, status: SettlementStatus, holder: AtomicLong) {
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
            counts.getValue(status).set(settlementRepository.countByStatus(status))
            // Absent oldest row means the state is empty — report 0, not a stale previous age.
            val oldest = settlementRepository.oldestCreatedAt(status)
            oldestAgeSeconds.getValue(status).set(
                oldest?.let { maxOf(0L, Duration.between(it, now).seconds) } ?: 0L,
            )
        }
        liveness?.recordSuccess()
    }

    companion object {
        const val SERVICE = "settlement"

        /** Dotted Micrometer names; Prometheus renders the underscored forms the rules read. */
        const val NON_TERMINAL_METRIC = "openbank.settlement.non_terminal"
        const val OLDEST_AGE_METRIC = "openbank.settlement.non_terminal.oldest.age.seconds"

        private const val WORKFLOW_NAME = "settlement-stranded-gauge"
        private const val REFRESH_INTERVAL_SECONDS = 30L

        /** The only two states a settlement is allowed to rest in. */
        val TERMINAL: Set<SettlementStatus> = setOf(SettlementStatus.BOOKED, SettlementStatus.REJECTED)

        /**
         * States a settlement must move out of: everything except [TERMINAL] — a settlement in any
         * of these has been accepted and is still owed an outcome, so its age is meaningful.
         *
         * Derived from the enum on purpose. A hand-kept list is silently wrong the day a status is
         * added, and the failure mode is a state nobody watches rather than a compile error.
         * [SettlementStatus.LEDGER_REVERSED] is deprecated and no longer written, but rows written
         * before #6037 still carry it, so it stays published until they are migrated out.
         */
        val NON_TERMINAL: List<SettlementStatus> = SettlementStatus.entries.filterNot { it in TERMINAL }
    }
}
