// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.observability

import com.openbank.sanctions.application.port.out.MonitoringRegistryStats
import com.openbank.sanctions.application.port.out.SanctionsMonitoringMetricsPort
import com.openbank.sanctions.application.usecase.SanctionsMonitoringSettings
import com.openbank.sanctions.domain.model.MonitoringFinding
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Meters of ongoing monitoring (ADR-0376), each named for what the service can establish:
 *
 *  - [ENABLED] — 1 or 0, the flag itself. Every other gauge is meaningless without it.
 *  - [SUBJECTS] — rows in the registry, i.e. subjects a caller registered. Not "customers
 *    screened": a subject is covered only once the sweep has reached it, which [SWEEP_LAG] says.
 *  - [DELTA_PENDING] — changed list entries not yet matched against the registry.
 *  - [SWEEP_LAG] — how many seconds the least recently swept subject is OVERDUE, 0 while the
 *    sweep keeps up with its interval. A never-swept subject counts from its registration.
 *  - [FINDINGS] — findings actually written, by match kind and detection path. A match that was
 *    already found or is covered by a clearance is not counted, because nothing was raised.
 *  - [RUNS] — scheduled runs by path and outcome. `disabled` is its own outcome: a tick that
 *    did nothing because the flag is off never adds to `completed`.
 *
 * The three registry gauges read NaN until a run has observed the registry. While monitoring is
 * disabled no run does, so they stay NaN: unknown is not zero.
 *
 * `@Startup`: the gauges must exist from boot, and nothing else would create this lazy bean.
 */
@Startup
@ApplicationScoped
class SanctionsMonitoringMetrics(
    private val registry: MeterRegistry,
    private val settings: SanctionsMonitoringSettings,
) : SanctionsMonitoringMetricsPort {

    private class Observation(val stats: MonitoringRegistryStats, val pendingEntries: Long)

    private val observed = AtomicReference<Observation?>(null)

    @PostConstruct
    fun register() {
        gauge(ENABLED) { if (settings.enabled) 1.0 else 0.0 }
        gauge(SUBJECTS) { observed.get()?.stats?.subjects?.toDouble() ?: Double.NaN }
        gauge(DELTA_PENDING) { observed.get()?.pendingEntries?.toDouble() ?: Double.NaN }
        gauge(SWEEP_LAG) {
            observed.get()?.let { observation ->
                (observation.stats.oldestSweepAgeSeconds - settings.sweepInterval.seconds).coerceAtLeast(0).toDouble()
            } ?: Double.NaN
        }
    }

    private fun gauge(name: String, value: () -> Double) {
        Gauge.builder(name, value).strongReference(true).register(registry)
    }

    override fun findingRaised(finding: MonitoringFinding) {
        registry.counter(
            FINDINGS,
            "match_kind", finding.matchKind.name.lowercase(Locale.ROOT),
            "detection", finding.detection.name.lowercase(Locale.ROOT),
        ).increment()
    }

    override fun runFinished(path: String, outcome: MonitoringRunOutcome) {
        registry.counter(RUNS, "path", path, "outcome", outcome.name.lowercase(Locale.ROOT)).increment()
    }

    override fun registryObserved(stats: MonitoringRegistryStats, pendingEntries: Long) {
        observed.set(Observation(stats, pendingEntries))
    }

    companion object {
        const val ENABLED: String = "openbank.sanctions.monitoring.enabled"
        const val SUBJECTS: String = "openbank.sanctions.monitoring.subjects"
        const val DELTA_PENDING: String = "openbank.sanctions.monitoring.delta.pending"
        const val SWEEP_LAG: String = "openbank.sanctions.monitoring.sweep.lag_seconds"
        const val FINDINGS: String = "openbank.sanctions.monitoring.findings"
        const val RUNS: String = "openbank.sanctions.monitoring.runs"
    }
}
