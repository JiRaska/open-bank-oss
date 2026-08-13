// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.infrastructure.retention

import com.openbank.cardissuance.application.port.out.CardRepository
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
 * ADR-0237: the card-PII retention sweep must publish a liveness heartbeat, and the heartbeat must
 * move ONLY when a sweep actually anonymised.
 *
 * This sweep is the only thing enforcing an AML-mandated anonymisation deadline, and it is exactly
 * the shape that fails invisibly: nothing throws when the schedule stops firing, and "0 cards
 * anonymised" is also what a healthy quiet day looks like. Without the heartbeat, a permanently
 * dead sweep and a clean one are externally identical while PII that should have been anonymised
 * years ago stays on disk.
 */
class CardPiiRetentionWorkflowLivenessTest {

    private val cardRepository = mockk<CardRepository>()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneOffset.UTC)

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
        CardPiiRetentionScheduler(
            cardRepository = cardRepository,
            clock = fixedClock,
            retentionYears = RETENTION_YEARS,
            dryRun = dryRun,
            enabled = enabled,
            domainMetrics = metrics,
        )

    @Test
    fun `registers the gauges at startup and records success after a real anonymisation`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { cardRepository.anonymizeExpiredCardPii(any()) } returns 4

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
    fun `a sweep that anonymises nothing still records success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { cardRepository.anonymizeExpiredCardPii(any()) } returns 0

        val scheduler = scheduler(metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())
        scheduler.enforceRetention()

        // A quiet run IS a successful run. Asserted on the success FLAG, not the age: the boot
        // seed already puts the age under the tolerance before enforceRetention() is called, so an
        // age assertion alone would hold against a scheduler that recorded nothing at all.
        assertThat(successRecordedOf(registry))
            .describedAs("a zero-row sweep still records a success")
            .isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a failing anonymisation records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { cardRepository.anonymizeExpiredCardPii(any()) } throws IllegalStateException("db down")

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

        // ABSENT, not merely stale — a heartbeat here would assert a retention policy this
        // environment is deliberately not enforcing.
        assertThat(ageOf(registry)).isNull()
        assertThat(successRecordedOf(registry)).isNull()
    }

    @Test
    fun `a dry-run sweep registers no gauge, and its preview run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()

        val scheduler = scheduler(metricsOver(registry), dryRun = true)
        scheduler.registerLiveness(StartupEvent())
        scheduler.enforceRetention()

        // A preview anonymises nothing, so a heartbeat would claim exactly the work it did not do.
        assertThat(ageOf(registry)).isNull()
        assertThat(successRecordedOf(registry)).isNull()
    }

    private companion object {
        const val WORKFLOW = "card-pii-retention"
        const val RETENTION_YEARS = 5L
        const val TOLERANCE_SECONDS = 5.0
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
