// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.domain.model.SddMandate
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SddWorkflowLivenessTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry, workflow: String): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

    private fun successRecordedOf(registry: MeterRegistry, workflow: String): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

    @Test
    fun `mandate expiry registers liveness and collapses after a successful run`() {
        val registry = SimpleMeterRegistry()
        val mandates = mockk<SddMandateRepository>()
        every { mandates.listLive() } returns Uni.createFrom().item(emptyList<SddMandate>())
        val scheduler = MandateExpiryScheduler(
            mandates = mandates,
            clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
            enabled = true,
            domainMetrics = metricsOver(registry),
        )

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "sdd-mandate-expiry")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "sdd-mandate-expiry")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "sdd-mandate-expiry")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.sweep().await().indefinitely()

        assertThat(ageOf(registry, "sdd-mandate-expiry")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed mandate expiry run records no success`() {
        val registry = SimpleMeterRegistry()
        val mandates = mockk<SddMandateRepository>()
        every { mandates.listLive() } returns Uni.createFrom().failure(IllegalStateException("db down"))
        val scheduler = MandateExpiryScheduler(
            mandates = mandates,
            clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
            enabled = true,
            domainMetrics = metricsOver(registry),
        )
        scheduler.onStart(StartupEvent())

        runCatching { scheduler.sweep().await().indefinitely() }

        assertThat(successRecordedOf(registry, "sdd-mandate-expiry"))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val TOLERANCE_SECONDS = 5.0
        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
