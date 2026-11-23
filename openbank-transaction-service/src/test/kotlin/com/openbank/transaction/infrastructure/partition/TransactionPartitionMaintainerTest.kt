// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.partition

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionMaintenanceReport
import com.openbank.libs.persistence.partition.PartitionPolicy
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TransactionPartitionMaintainerTest {

    private val clock = Clock.fixed(Instant.parse("2026-12-30T22:00:00Z"), ZoneOffset.UTC)
    private val executor = mockk<HibernatePartitionExecutor>()
    private val registry = SimpleMeterRegistry()
    private val metrics = DomainMetrics().apply {
        registryInstance = mockk<Instance<MeterRegistry>> {
            every { isResolvable } returns true
            every { get() } returns registry
        }
    }

    private fun successRecorded(): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "transaction-partition-maintenance")
        .gauge()
        ?.value()
    private val maintainer = TransactionPartitionMaintainer(
        clock = clock,
        executor = executor,
        futureYears = 2,
        clusterLock = NoOpClusterLock(),
        domainMetrics = metrics,
    ).apply { onStart(mockk(relaxed = true)) }

    @AfterEach
    fun cleanup() {
        unmockkObject(PartitionMaintenance)
    }

    @Test
    fun `runs roll-forward for today with retention forced to dry-run and records success`(): Unit = runBlocking {
        mockkObject(PartitionMaintenance)
        val policy = slot<PartitionPolicy>()
        coEvery {
            PartitionMaintenance.maintain(LocalDate.of(2026, 12, 30), capture(policy), executor)
        } returns PartitionMaintenanceReport(emptyList(), emptyList(), 0)

        maintainer.maintain()

        coVerify(exactly = 1) { PartitionMaintenance.maintain(LocalDate.of(2026, 12, 30), any(), executor) }
        assertThat(policy.captured.parentTable).isEqualTo("transactions")
        assertThat(policy.captured.prefix).isEqualTo("transactions")
        assertThat(policy.captured.futureYears).isEqualTo(2)
        assertThat(policy.captured.dryRun).describedAs("retention must never execute DETACH/DROP").isTrue()
        assertThat(policy.captured.dropEnabled).isFalse()
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `a maintenance failure is swallowed and records no success`(): Unit = runBlocking {
        mockkObject(PartitionMaintenance)
        coEvery {
            PartitionMaintenance.maintain(any(), any<PartitionPolicy>(), executor)
        } throws IllegalStateException("db down")

        maintainer.maintain()

        assertThat(successRecorded()).describedAs("a failed pass must leave liveness unrecorded").isEqualTo(0.0)
    }
}
