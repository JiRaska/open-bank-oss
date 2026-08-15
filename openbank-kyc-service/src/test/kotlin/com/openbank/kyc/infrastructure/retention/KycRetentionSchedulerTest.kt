// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.retention

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class KycRetentionSchedulerTest {

    private val kycCaseRepository = mockk<KycCaseRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2031-06-15T03:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `deletes erased KYC cases whose hold period has expired`(): Unit = runBlocking {
        // Must use calendar-year arithmetic (minusYears), not 365-day arithmetic, to match
        // the scheduler's AML-safe cutoff calculation.
        val expectedCutoff = LocalDate.of(2031, 6, 15).minusYears(5)
            .atStartOfDay(ZoneOffset.UTC).toInstant() // 2026-06-15T00:00:00Z
        coEvery { kycCaseRepository.deleteErasedCasesOlderThan(expectedCutoff) } returns 2L

        scheduler(retentionYears = 5).enforceRetention()

        coVerify(exactly = 1) { kycCaseRepository.deleteErasedCasesOlderThan(expectedCutoff) }
    }

    @Test
    fun `dry-run does not call the repository`(): Unit = runBlocking {
        scheduler(dryRun = true).enforceRetention()

        coVerify(exactly = 0) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    @Test
    fun `disabled scheduler does not call the repository`(): Unit = runBlocking {
        scheduler(enabled = false).enforceRetention()

        coVerify(exactly = 0) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    @Test
    fun `zero rows deleted is a no-op (no exception)`(): Unit = runBlocking {
        coEvery { kycCaseRepository.deleteErasedCasesOlderThan(any()) } returns 0L

        scheduler().enforceRetention()

        coVerify(exactly = 1) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    @Test
    fun `records liveness only after the retention delete completes`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { kycCaseRepository.deleteErasedCasesOlderThan(any()) } returns 0L
        val scheduler = scheduler(domainMetrics = metricsOver(registry))

        scheduler.registerLiveness(StartupEvent())
        assertThat(successRecordedOf(registry)).isEqualTo(0.0)

        scheduler.enforceRetention()

        assertThat(successRecordedOf(registry)).isEqualTo(1.0)
    }

    @Test
    fun `dry-run and disabled schedules do not record liveness success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val metrics = metricsOver(registry)

        val dryRunScheduler = scheduler(dryRun = true, domainMetrics = metrics)
        dryRunScheduler.registerLiveness(StartupEvent())
        dryRunScheduler.enforceRetention()
        assertThat(successRecordedOf(registry)).isEqualTo(0.0)

        val disabledRegistry = SimpleMeterRegistry()
        val disabledScheduler = scheduler(
            enabled = false,
            domainMetrics = metricsOver(disabledRegistry),
        )
        disabledScheduler.registerLiveness(StartupEvent())
        disabledScheduler.enforceRetention()
        assertThat(successRecordedOf(disabledRegistry)).isEqualTo(0.0)
    }

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "kyc-retention")
        .gauge()
        ?.value()

    private fun scheduler(
        retentionYears: Long = 5,
        dryRun: Boolean = false,
        enabled: Boolean = true,
        domainMetrics: DomainMetrics = mockk(relaxed = true),
    ): KycRetentionScheduler = KycRetentionScheduler(
        kycCaseRepository = kycCaseRepository,
        clock = fixedClock,
        retentionYears = retentionYears,
        dryRun = dryRun,
        enabled = enabled,
        domainMetrics = domainMetrics,
    )
}
