// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.`in`.NostroBreakUseCase
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
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-0315 D7: the nostro break sweep. Re-reconciles recent statements (so a late ledger booking
 * resolves its break), ages every open break, alerts ONCE per break over the threshold through the
 * outbox, and publishes `openbank_treasury_nostro_breaks_open` / `_aged` for the alert rule.
 *
 * Both gauges read NaN until the first sweep completes: a cold pod does not KNOW the count, and a
 * 0 would claim "no aged breaks" it never measured — NaN makes `> 0` false without asserting it.
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method has no Vert.x context and a
 * `runBlocking` around reactive Panache throws HR000068 (#2148; check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class NostroBreakScheduler(
    private val breaks: NostroBreakUseCase,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.nostro-breaks.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(NostroBreakScheduler::class.java)

    private val openCount = AtomicReference(Double.NaN)
    private val agedCount = AtomicReference(Double.NaN)

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registered at boot, not lazily on first fire: an absent gauge is not a stale one (ADR-0237). */
    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!enabled) return
        Gauge.builder(OPEN_GAUGE, openCount) { it.get() }
            .description("Open nostro reconciliation breaks after the last sweep (ADR-0315 D7)")
            .register(registry)
        Gauge.builder(AGED_GAUGE, agedCount) { it.get() }
            .description("Open nostro breaks over the age and amount alert threshold (ADR-0315 D7)")
            .register(registry)
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.nostro-breaks.interval:1h}",
        delayed = "\${openbank.treasury.nostro-breaks.initial-delay:90s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-nostro-break-sweep",
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { breaks.sweep(AccountingClock.bank(clock).today()) }
            .onSuccess { r ->
                openCount.set(r.open.toDouble())
                agedCount.set(r.aged.toDouble())
                r.failures.forEach { log.error("nostro break sweep could not observe a statement", it) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
                if (r.alerted > 0) log.warnf("nostro break sweep: %d break(s) newly aged past the threshold", r.alerted)
            }
            .onFailure { log.error("nostro break sweep failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-nostro-break-sweep"
        const val OPEN_GAUGE = "openbank.treasury.nostro.breaks.open"
        const val AGED_GAUGE = "openbank.treasury.nostro.breaks.aged"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
