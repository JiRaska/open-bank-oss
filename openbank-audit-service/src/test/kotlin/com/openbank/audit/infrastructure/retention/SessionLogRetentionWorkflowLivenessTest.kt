// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.infrastructure.retention

import com.openbank.audit.application.port.out.SessionLogRepositoryPort
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-0237: the session-log retention sweep must publish a liveness heartbeat, and the heartbeat
 * must move ONLY when a sweep actually deleted.
 *
 * The interesting case here is not the failure path but the **registration condition**. This
 * scheduler ships disabled (`enabled=false`) and preview-only (`dryRun=true`) by default, and a
 * gauge published under either of those states would assert precisely the retention the service is
 * not performing — an operator reading a fresh heartbeat would conclude session logs are being
 * purged when nothing has been deleted. So the gauge is registered only when the sweep will really
 * delete, and **absent** is the honest signal for "this environment does not run this job". Absent
 * is a different state from stale, and the ADR-0237 staleness rule alerts only on stale.
 */
class SessionLogRetentionWorkflowLivenessTest {

    private val sessionLogRepository = mockk<SessionLogRepositoryPort>()
    private val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneOffset.UTC)

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

    private fun scheduler(metrics: DomainMetrics, dryRun: Boolean = false, enabled: Boolean = true) =
        SessionLogRetentionScheduler(
            sessionLogRepository = sessionLogRepository,
            auditPublisher = auditPublisher,
            clock = fixedClock,
            retentionDays = RETENTION_DAYS,
            dryRun = dryRun,
            enabled = enabled,
            domainMetrics = metrics,
        )

    @Test
    fun `registers the gauges at startup and records success after a real delete`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { sessionLogRepository.deleteOlderThan(any()) } returns 7

        val scheduler = scheduler(metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())

        // Registered but not yet succeeded. The age gauge is SEEDED AT REGISTRATION (#4208), so a
        // never-run job reads as old as its pod rather than the ~1.8e9 seconds Instant.EPOCH
        // produced — the value that made WorkflowLivenessStale fire 15 minutes after every deploy.
        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.enforceRetention()

        assertThat(successRecordedOf(registry)).isEqualTo(SUCCEEDED)
        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a sweep that deletes nothing still records success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { sessionLogRepository.deleteOlderThan(any()) } returns 0

        val scheduler = scheduler(metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())
        scheduler.enforceRetention()

        // A quiet run IS a successful run — the heartbeat tracks the schedule, not the workload.
        // Asserted on the success FLAG, not the age: the boot seed already puts the age under the
        // tolerance before enforceRetention() is called, so an age assertion alone would hold
        // against a scheduler that recorded nothing at all.
        assertThat(successRecordedOf(registry))
            .describedAs("a zero-row sweep still records a success")
            .isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a failing delete records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { sessionLogRepository.deleteOlderThan(any()) } throws IllegalStateException("db down")

        val scheduler = scheduler(metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())

        runCatching { scheduler.enforceRetention() }

        assertThat(successRecordedOf(registry))
            .describedAs("a failed sweep must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a disabled sweep registers no gauge at all`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()

        scheduler(metricsOver(registry), enabled = false).registerLiveness(StartupEvent())

        // ABSENT, not merely stale. A heartbeat here would assert a retention policy this
        // environment is deliberately not enforcing, and absent is the signal that says so.
        assertThat(ageOf(registry))
            .describedAs("a disabled sweep must publish no heartbeat")
            .isNull()
        assertThat(successRecordedOf(registry)).isNull()
    }

    @Test
    fun `a dry-run sweep registers no gauge, and its preview run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { sessionLogRepository.countOlderThan(any()) } returns 12

        val scheduler = scheduler(metricsOver(registry), dryRun = true)
        scheduler.registerLiveness(StartupEvent())
        scheduler.enforceRetention()

        // A preview deletes nothing, so a heartbeat would claim exactly the work it did not do.
        assertThat(ageOf(registry))
            .describedAs("a dry-run sweep must publish no heartbeat")
            .isNull()
        assertThat(successRecordedOf(registry)).isNull()
    }

    private companion object {
        const val WORKFLOW = "audit-session-log-retention"
        const val RETENTION_DAYS = 90L
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet and astronomically below the ~1.8e9 the EPOCH seed
        // produced, so it fails loudly if that seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
