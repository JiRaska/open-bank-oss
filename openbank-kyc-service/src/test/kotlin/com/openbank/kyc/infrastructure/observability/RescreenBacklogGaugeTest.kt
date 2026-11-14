// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.port.out.ScreeningTriggerBacklog
import com.openbank.kyc.application.port.out.ScreeningTriggerRepository
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Cold-pod, enabled/disabled and failure readings of [RescreenBacklogGauge] (#15961). That the
 * values come from real rows is RescreenBacklogGaugeIT's job; this pins the state machine.
 */
class RescreenBacklogGaugeTest {

    private val repository = mockk<ScreeningTriggerRepository>()

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun gaugeFor(loopEnabled: Boolean, registry: MeterRegistry = SimpleMeterRegistry()) =
        RescreenBacklogGauge(repository, registry, metricsOver(registry), loopEnabled).also { it.register() }

    private fun read(registry: MeterRegistry, name: String): Double? =
        registry.find(name).tag("service", "kyc").gauge()?.value()

    @Test
    fun `cold pod reads NaN on every series - neither empty-and-healthy nor decades old`() {
        val registry = SimpleMeterRegistry()
        gaugeFor(loopEnabled = false, registry)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_PENDING)).isNaN()
        assertThat(read(registry, RescreenBacklogGauge.METRIC_OLDEST_AGE)).isNaN()
        assertThat(read(registry, RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isNaN()
    }

    @Test
    fun `enabled loop with a backlog publishes count and age and is not disabled-with-pending`() {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeFor(loopEnabled = true, registry)
        coEvery { repository.backlog() } returns ScreeningTriggerBacklog(pending = 3, oldestAgeSeconds = 7200)
        runBlocking { gauge.sample() }
        assertThat(read(registry, RescreenBacklogGauge.METRIC_PENDING)).isEqualTo(3.0)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_OLDEST_AGE)).isEqualTo(7200.0)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(0.0)
    }

    @Test
    fun `disabled loop with a backlog reads 1, disabled and empty reads 0`() {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeFor(loopEnabled = false, registry)
        coEvery { repository.backlog() } returns ScreeningTriggerBacklog(pending = 2, oldestAgeSeconds = 60)
        runBlocking { gauge.sample() }
        assertThat(read(registry, RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(1.0)

        coEvery { repository.backlog() } returns ScreeningTriggerBacklog(pending = 0, oldestAgeSeconds = 0)
        runBlocking { gauge.sample() }
        assertThat(read(registry, RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(0.0)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_PENDING)).isEqualTo(0.0)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_OLDEST_AGE)).isEqualTo(0.0)
    }

    @Test
    fun `a failed sample keeps the previous values instead of clearing a firing alert`() {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeFor(loopEnabled = true, registry)
        coEvery { repository.backlog() } returns ScreeningTriggerBacklog(pending = 4, oldestAgeSeconds = 90_000)
        runBlocking { gauge.sample() }
        coEvery { repository.backlog() } throws IllegalStateException("kyc-db down")
        runBlocking { gauge.sample() }
        assertThat(read(registry, RescreenBacklogGauge.METRIC_PENDING)).isEqualTo(4.0)
        assertThat(read(registry, RescreenBacklogGauge.METRIC_OLDEST_AGE)).isEqualTo(90_000.0)
    }

    @Test
    fun `a failure on a cold pod stays NaN - never a guessed zero`() {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeFor(loopEnabled = true, registry)
        coEvery { repository.backlog() } throws IllegalStateException("kyc-db down")
        runBlocking { gauge.sample() }
        assertThat(read(registry, RescreenBacklogGauge.METRIC_PENDING)).isNaN()
    }
}
