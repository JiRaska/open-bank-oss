// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.ledger.infrastructure.partition.HibernatePartitionExecutor
import com.openbank.ledger.infrastructure.partition.JournalPartitionMaintainer
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionMaintenanceReport
import com.openbank.libs.persistence.partition.PartitionPolicy
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class LedgerWorkflowLivenessAdditionsTest {

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

    @AfterEach
    fun cleanup() {
        unmockkObject(PartitionMaintenance)
    }

    @Test
    fun `journal partition maintainer registers liveness and collapses after a successful run`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
        val executor = mockk<HibernatePartitionExecutor>()
        mockkObject(PartitionMaintenance)
        coEvery {
            PartitionMaintenance.maintain(LocalDate.of(2026, 7, 18), any<PartitionPolicy>(), executor)
        } returns PartitionMaintenanceReport(emptyList(), emptyList(), 0)
        val scheduler = JournalPartitionMaintainer(
            clock,
            executor,
            futureYears = 2,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = true,
            clusterLock = NoOpClusterLock(),
            domainMetrics = metricsOver(registry),
        )

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "ledger-journal-partition-maintenance")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "ledger-journal-partition-maintenance")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.maintain()

        assertThat(ageOf(registry, "ledger-journal-partition-maintenance")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed journal partition maintenance run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
        val executor = mockk<HibernatePartitionExecutor>()
        mockkObject(PartitionMaintenance)
        coEvery { PartitionMaintenance.maintain(any(), any<PartitionPolicy>(), executor) } throws
            IllegalStateException("ddl failed")
        val scheduler = JournalPartitionMaintainer(
            clock,
            executor,
            futureYears = 2,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = true,
            clusterLock = NoOpClusterLock(),
            domainMetrics = metricsOver(registry),
        )
        scheduler.onStart(StartupEvent())

        scheduler.maintain()

        assertThat(ageOf(registry, "ledger-journal-partition-maintenance")!!).isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    @Test
    fun `tieout freshness watchdog registers liveness and collapses after a successful run`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
        val runs = mockk<TieOutRunRepository>()
        coEvery { runs.findLatest() } returns TieOutRunRecord(
            id = UUID.randomUUID(),
            asOf = LocalDate.of(2026, 7, 15),
            runAt = Instant.parse("2026-07-16T06:00:00Z"),
            status = TieOutRunStatus.OK,
            accountsChecked = 4,
            breaks = 0,
            errors = 0,
        )
        val watchdog = TieOutFreshnessWatchdog(runs, clock, NoOpClusterLock(), metricsOver(registry))

        watchdog.onStart(StartupEvent())

        val neverRan = ageOf(registry, "ledger-tieout-freshness")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "ledger-tieout-freshness")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofHours(1).toSeconds().toDouble())

        watchdog.checkFreshness()

        assertThat(ageOf(registry, "ledger-tieout-freshness")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed tieout freshness watchdog run records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
        val runs = mockk<TieOutRunRepository>()
        coEvery { runs.findLatest() } throws IllegalStateException("db down")
        val watchdog = TieOutFreshnessWatchdog(runs, clock, NoOpClusterLock(), metricsOver(registry))
        watchdog.onStart(StartupEvent())

        runCatching { watchdog.checkFreshness() }

        assertThat(ageOf(registry, "ledger-tieout-freshness")!!).isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    private companion object {
        const val TOLERANCE_SECONDS = 5.0
        val FIFTY_YEARS_SECONDS = Duration.ofDays(50 * 365).toSeconds().toDouble()
    }
}
