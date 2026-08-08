// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.CurrencyReconciliation
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class BalanceWorkflowLivenessTest {

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

    private fun report(clock: Clock, hasDrift: Boolean = false) = ReconciliationReport(
        asOf = LocalDate.of(2026, 7, 18),
        generatedAt = OffsetDateTime.now(clock),
        tolerance = BigDecimal.ZERO,
        currencies = listOf(
            CurrencyReconciliation(
                currency = "CZK",
                ledgerControlBalance = BigDecimal.TEN,
                subLedgerBookedSum = if (hasDrift) BigDecimal.ONE else BigDecimal.TEN,
                difference = if (hasDrift) BigDecimal("-9") else BigDecimal.ZERO,
                withinTolerance = !hasDrift,
            ),
        ),
    )

    @Test
    fun `balance reconciliation registers liveness at startup and collapses on a successful run`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
        val reconcile = mockk<ReconcileBalancesUseCase>()
        coEvery { reconcile.reconcile(LocalDate.of(2026, 7, 18)) } returns report(clock)
        val job = BalanceReconciliationScheduler(reconcile, clock, NoOpClusterLock(), metricsOver(registry))

        job.onStart(io.quarkus.runtime.StartupEvent())

        val neverRan = ageOf(registry, "balance-reconciliation")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "balance-reconciliation")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "balance-reconciliation")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        job.runDaily()

        assertThat(ageOf(registry, "balance-reconciliation")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed balance reconciliation run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
        val reconcile = mockk<ReconcileBalancesUseCase>()
        coEvery { reconcile.reconcile(any()) } throws IllegalStateException("db down")
        val job = BalanceReconciliationScheduler(reconcile, clock, NoOpClusterLock(), metricsOver(registry))
        job.onStart(io.quarkus.runtime.StartupEvent())

        job.runDaily()

        assertThat(successRecordedOf(registry, "balance-reconciliation"))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `reconciliation freshness watchdog registers liveness at startup and collapses on a successful run`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
            val records = mockk<ReconciliationRecordRepository>()
            coEvery { records.findLatest() } returns null
            val watchdog = ReconciliationFreshnessWatchdog(records, clock, NoOpClusterLock(), metricsOver(registry))

            watchdog.onStart(io.quarkus.runtime.StartupEvent())

            val neverRan = ageOf(registry, "balance-reconciliation-freshness")
            assertThat(neverRan).isNotNull()
            assertThat(neverRan!!)
                .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
                .isLessThan(BOOT_SEED_CEILING_SECONDS)
            assertThat(successRecordedOf(registry, "balance-reconciliation-freshness")).isEqualTo(NOT_YET_SUCCEEDED)
            assertThat(
                registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                    .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "balance-reconciliation-freshness")
                    .gauge()?.value(),
            ).isEqualTo(Duration.ofHours(1).toSeconds().toDouble())

            watchdog.checkFreshness()

            assertThat(ageOf(registry, "balance-reconciliation-freshness")!!).isLessThan(TOLERANCE_SECONDS)
        }

    @Test
    fun `a failed freshness watchdog run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
        val records = mockk<ReconciliationRecordRepository>()
        coEvery { records.findLatest() } throws IllegalStateException("repo down")
        val watchdog = ReconciliationFreshnessWatchdog(records, clock, NoOpClusterLock(), metricsOver(registry))
        watchdog.onStart(io.quarkus.runtime.StartupEvent())

        runCatching { watchdog.checkFreshness() }

        assertThat(successRecordedOf(registry, "balance-reconciliation-freshness"))
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
