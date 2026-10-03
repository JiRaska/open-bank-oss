// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.risk.application.usecase.ReferenceCurveSetSeeder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Keeps the sandbox's snapshot runs supplied with a curve set (ADR-0313 D4): every few minutes,
 * each recent run's as-of date gets the illustrative [com.openbank.risk.domain.curve.ReferenceCurveSet]
 * if it has none yet. Without it IRRBB, cash flows and the liquidity forecast have nothing to
 * select — every one of them needs a curve set as of the run's own date, and none had ever been
 * uploaded.
 *
 * Gated behind `openbank.risk.reference-curves.enabled` (default `false`: demo quotes appear only
 * where an environment asks for them), and the seeder itself refuses under `production`
 * provenance. Idempotent by the set's deterministic id, so overlapping ticks or two replicas
 * write each date once.
 *
 * `suspend`, never `runBlocking` (#2148/#2187); exercised through the real scheduler by
 * `ReferenceCurveSetSchedulerVertxContextIT`.
 */
@ApplicationScoped
class ReferenceCurveSetScheduler(
    private val seeder: ReferenceCurveSetSeeder,
    @ConfigProperty(name = "openbank.risk.reference-curves.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(ReferenceCurveSetScheduler::class.java)

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @Inject
    lateinit var meterRegistry: MeterRegistry

    private var liveness: WorkflowLivenessRecorder? = null
    private var createdCounter: Counter? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        if (!enabled) return
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        createdCounter = Counter.builder(CREATED_COUNTER)
            .description("Sandbox reference curve sets created, one per snapshot as-of date (demo data).")
            .register(meterRegistry)
    }

    @Scheduled(
        every = "{openbank.risk.reference-curves.every:10m}",
        delayed = "{openbank.risk.reference-curves.delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun seed() {
        if (!enabled) return
        val outcome = seeder.seedRecentRunDates()
        if (outcome.created.isNotEmpty()) {
            createdCounter?.increment(outcome.created.size.toDouble())
            log.infof(
                "Seeded sandbox reference curve sets (demo data) for %s; %d date(s) already had one",
                outcome.created,
                outcome.alreadyPresent,
            )
        }
        liveness?.recordSuccess()
    }

    private companion object {
        const val WORKFLOW_NAME = "risk-engine-reference-curves"
        const val CREATED_COUNTER = "openbank.risk.reference_curve_sets.created"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
