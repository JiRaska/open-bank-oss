// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.infrastructure.rest

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.securityscanner.application.SecurityScannerService
import com.openbank.securityscanner.domain.PlatformSecurityReport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class SecurityScannerWorkflowLivenessTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    private fun configWithOneService(): SecurityScannerConfig {
        val entry = mockk<SecurityScannerConfig.ServiceEntry>()
        every { entry.name() } returns "svc"
        every { entry.url() } returns "http://localhost"
        every { entry.port() } returns 8080
        val config = mockk<SecurityScannerConfig>()
        every { config.services() } returns listOf(entry)
        return config
    }

    @Test
    fun `scheduled scan registers gauge at startup and records success after scan`() {
        val registry = SimpleMeterRegistry()
        val scanner = mockk<SecurityScannerService>()
        every { scanner.scanAll(any()) } returns mockk<PlatformSecurityReport>(relaxed = true)
        val resource = SecurityScannerResource(scanner, configWithOneService(), metricsOver(registry))

        resource.registerLiveness()

        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofMinutes(30).toSeconds().toDouble())

        resource.scheduledScan()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed scan records no success`() {
        val registry = SimpleMeterRegistry()
        val scanner = mockk<SecurityScannerService>()
        every { scanner.scanAll(any()) } throws IllegalStateException("scan failed")
        val resource = SecurityScannerResource(scanner, configWithOneService(), metricsOver(registry))
        resource.registerLiveness()

        assertThatThrownBy { resource.scheduledScan() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "security-scanner-scan"
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
