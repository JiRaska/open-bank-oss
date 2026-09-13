// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Micrometer samples gauge suppliers on the scrape thread while the counts come from a suspend
 * repository, so the cached-AtomicLong indirection is the whole design — an assertion on the
 * repository call proves nothing about what a scrape would see. These read the registry.
 */
class OnboardingFunnelGaugeTest {

    private val repository = mockk<OnboardingRepository>()

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun stageGauge(registry: MeterRegistry, stage: FunnelStage): Double? =
        registry.find("openbank.onboarding.funnel")
            .tag("service", "onboarding")
            .tag("stage", stage.name)
            .gauge()
            ?.value()

    private fun successRecorded(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "onboarding-funnel-gauge-refresh")
        .gauge()
        ?.value()

    private fun gaugeOver(registry: MeterRegistry?, metricsRegistry: MeterRegistry) =
        OnboardingFunnelGauge(repository, registry)
            .apply { domainMetrics = metricsOver(metricsRegistry) }
            .also { it.register() }

    @Test
    fun `every funnel stage gets a series, including the ones with no records`() {
        val registry = SimpleMeterRegistry()
        gaugeOver(registry, registry)

        // A stage with no series is a missing board column, not an empty one — the dashboard
        // cannot tell "zero blocked parties" from "the gauge was never registered".
        FunnelStage.entries.forEach { assertThat(stageGauge(registry, it)).isEqualTo(0.0) }
    }

    @Test
    fun `refresh publishes the per-stage count the repository reports`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeOver(registry, registry)
        FunnelStage.entries.forEach { coEvery { repository.countByStage(it) } returns 0L }
        coEvery { repository.countByStage(FunnelStage.KYC_UNDER_REVIEW) } returns 12L
        coEvery { repository.countByStage(FunnelStage.BLOCKED) } returns 4L

        gauge.refresh()

        assertThat(stageGauge(registry, FunnelStage.KYC_UNDER_REVIEW)).isEqualTo(12.0)
        assertThat(stageGauge(registry, FunnelStage.BLOCKED)).isEqualTo(4.0)
        assertThat(stageGauge(registry, FunnelStage.ACTIVE)).isEqualTo(0.0)
    }

    @Test
    fun `a stage that drains back to zero publishes zero, not its previous value`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeOver(registry, registry)
        FunnelStage.entries.forEach { coEvery { repository.countByStage(it) } returns 0L }
        coEvery { repository.countByStage(FunnelStage.SCA_PENDING) } returns 9L
        gauge.refresh()
        assertThat(stageGauge(registry, FunnelStage.SCA_PENDING)).isEqualTo(9.0)

        coEvery { repository.countByStage(FunnelStage.SCA_PENDING) } returns 0L
        gauge.refresh()

        assertThat(stageGauge(registry, FunnelStage.SCA_PENDING)).isEqualTo(0.0)
    }

    @Test
    fun `a completed refresh records a liveness success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeOver(registry, registry)
        FunnelStage.entries.forEach { coEvery { repository.countByStage(it) } returns 1L }
        assertThat(successRecorded(registry)).isEqualTo(0.0)

        gauge.refresh()

        assertThat(successRecorded(registry)).isEqualTo(1.0)
    }

    @Test
    fun `a repository failure propagates and records no liveness success`() {
        val registry = SimpleMeterRegistry()
        val gauge = gaugeOver(registry, registry)
        FunnelStage.entries.forEach { coEvery { repository.countByStage(it) } returns 0L }
        coEvery { repository.countByStage(FunnelStage.BLOCKED) } throws IllegalStateException("db down")

        assertThatThrownBy { runBlocking { gauge.refresh() } }
            .isInstanceOf(IllegalStateException::class.java)

        // Staleness, not a fabricated success: a down database must not look like a fresh scan.
        assertThat(successRecorded(registry)).isEqualTo(0.0)
    }

    @Test
    fun `the liveness heartbeat is registered even when no meter registry is present`(): Unit = runBlocking {
        val livenessRegistry = SimpleMeterRegistry()
        val gauge = gaugeOver(null, livenessRegistry)
        FunnelStage.entries.forEach { coEvery { repository.countByStage(it) } returns 0L }

        gauge.refresh()

        assertThat(successRecorded(livenessRegistry)).isEqualTo(1.0)
    }
}
