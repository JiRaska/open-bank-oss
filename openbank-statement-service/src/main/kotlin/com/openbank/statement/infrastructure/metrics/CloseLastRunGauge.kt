// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.metrics

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.statement.application.port.out.CloseRunRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the **last finished close** as a Micrometer gauge
 * `openbank_statement_close_last_run_timestamp_seconds` (epoch seconds), driving the
 * StatementCloseCadenceStalled alert (ADR-0069 D3 / issue #470).
 *
 * Why a gauge and not the `openbank_statement_close_runs_total` counter: the cadence alert asks
 * "has a close run in the last 35 days?". A counter query (`max_over_time(...[35d])`) can only see
 * as far back as Prometheus retention — **12h in the sandbox** — so right after a legitimate monthly
 * run the 35d window already reads 0 and the alert fires forever (false positive). A gauge's current
 * value is always scrapeable regardless of retention, and because it is **derived from the persisted
 * run log** (not an in-memory counter) it survives pod restarts without any boot-time seeding dance.
 *
 * Mirrors [com.openbank.dispute.infrastructure.observability.ComplaintDeadlineGauge]: Micrometer
 * samples a gauge supplier synchronously on the Prometheus scrape (worker) thread, but the value comes
 * from a reactive query — so a scheduled tick refreshes a cached [AtomicLong] on a proper Vert.x
 * context (a reactive Panache read at `StartupEvent` has no context and fails) and the supplier reads
 * that cache cheaply and lock-free. The cache only ever advances (monotonic max), so an in-flight
 * RUNNING latest-run (no `finishedAt` yet) never regresses the gauge.
 *
 * Service-local `MeterRegistry` (null-safe via [Instance], exactly like libs `DomainMetrics`): this
 * meter is statement-specific, so it stays in this service rather than forcing a fleet-wide rebuild.
 */
@Startup
@ApplicationScoped
class CloseLastRunGauge(private val runs: CloseRunRepository, private val registry: MeterRegistry?) {
    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. in slim test slices). Without an explicit @Inject ctor, ArC sees two
    // constructors, registers no bean, and the @Startup hook + @Scheduled tick silently never run.
    @Inject
    constructor(runs: CloseRunRepository, registryInstance: Instance<MeterRegistry>) : this(
        runs,
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    /** Epoch seconds of the last finished close; 0 until the first refresh observes a finished run. */
    private val lastRunEpochSeconds = AtomicLong(0)

    @Inject
    lateinit var domainMetrics: DomainMetrics
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        val r = registry ?: return
        Gauge.builder(GAUGE_NAME, lastRunEpochSeconds) { it.get().toDouble() }
            .strongReference(true)
            .register(r)
    }

    @Scheduled(every = "60s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun refresh(): Uni<Void> = runs.latestRun()
        .onItem().invoke { run ->
            run?.finishedAt?.let { stamp(it.epochSecond) }
            liveness?.recordSuccess()
        }
        .replaceWithVoid()

    /** Advance the gauge monotonically; an in-flight RUNNING run (no finishedAt) is simply skipped. */
    private fun stamp(epochSeconds: Long) {
        lastRunEpochSeconds.getAndUpdate { prev -> maxOf(prev, epochSeconds) }
    }

    companion object {
        const val GAUGE_NAME = "openbank_statement_close_last_run_timestamp_seconds"
        private const val WORKFLOW_NAME = "statement-close-last-run-gauge-refresh"
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
