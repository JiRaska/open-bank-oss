// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.observability

import com.openbank.sanctions.application.port.out.MonitoringRegistryStats
import com.openbank.sanctions.application.usecase.SanctionsMonitoringSettings
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class SanctionsMonitoringMetricsTest {

    private val registry = SimpleMeterRegistry()

    private fun metrics(enabled: Boolean) = SanctionsMonitoringMetrics(
        registry,
        SanctionsMonitoringSettings(enabled, 20, 20_000, 5_000, 25, Duration.ofDays(30), Duration.ofMinutes(15)),
    ).also { it.register() }

    private fun gauge(name: String) = registry.get(name).gauge().value()

    @Test
    fun `registry gauges are unknown, not zero, until a run has observed the registry`() {
        metrics(enabled = false)

        assertThat(gauge(SanctionsMonitoringMetrics.ENABLED)).isEqualTo(0.0)
        assertThat(gauge(SanctionsMonitoringMetrics.SUBJECTS)).isNaN()
        assertThat(gauge(SanctionsMonitoringMetrics.DELTA_PENDING)).isNaN()
        assertThat(gauge(SanctionsMonitoringMetrics.SWEEP_LAG)).isNaN()
    }

    @Test
    fun `sweep lag is the overdue part of the oldest sweep age`() {
        val metrics = metrics(enabled = true)
        val interval = Duration.ofDays(30).seconds

        metrics.registryObserved(MonitoringRegistryStats(subjects = 7, oldestSweepAgeSeconds = interval - 5), 3)
        assertThat(gauge(SanctionsMonitoringMetrics.ENABLED)).isEqualTo(1.0)
        assertThat(gauge(SanctionsMonitoringMetrics.SUBJECTS)).isEqualTo(7.0)
        assertThat(gauge(SanctionsMonitoringMetrics.DELTA_PENDING)).isEqualTo(3.0)
        assertThat(gauge(SanctionsMonitoringMetrics.SWEEP_LAG)).isEqualTo(0.0)

        metrics.registryObserved(MonitoringRegistryStats(subjects = 7, oldestSweepAgeSeconds = interval + 90), 0)
        assertThat(gauge(SanctionsMonitoringMetrics.SWEEP_LAG)).isEqualTo(90.0)
    }

    @Test
    fun `a disabled run never counts as a completed one`() {
        val metrics = metrics(enabled = false)

        metrics.runFinished("delta", MonitoringRunOutcome.DISABLED)

        assertThat(
            registry.get(SanctionsMonitoringMetrics.RUNS).tags("path", "delta", "outcome", "disabled").counter().count(),
        ).isEqualTo(1.0)
        assertThat(registry.find(SanctionsMonitoringMetrics.RUNS).tags("outcome", "completed").counter()).isNull()
    }
}
