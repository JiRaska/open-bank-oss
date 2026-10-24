// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.partition

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionMaintenanceReport
import com.openbank.libs.persistence.partition.PartitionPolicy
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class JournalPartitionMaintainerTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
    private val executor = mockk<HibernatePartitionExecutor>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val scheduler = JournalPartitionMaintainer(
        clock = clock,
        executor = executor,
        futureYears = 2,
        retentionYears = 10,
        dropEnabled = false,
        dryRun = true,
        clusterLock = NoOpClusterLock(),
        domainMetrics = metrics,
    )

    @AfterEach
    fun cleanup() {
        unmockkObject(PartitionMaintenance)
    }

    @Test
    fun `runs maintenance for today's date from the injected clock`(): Unit = runBlocking {
        mockkObject(PartitionMaintenance)
        val expected = LocalDate.of(2026, 7, 18)
        coEvery {
            PartitionMaintenance.maintain(expected, any<PartitionPolicy>(), executor)
        } returns PartitionMaintenanceReport(emptyList(), emptyList(), 0)

        scheduler.maintain()

        coVerify(exactly = 1) { PartitionMaintenance.maintain(expected, any<PartitionPolicy>(), executor) }
    }

    @Test
    fun `a maintenance failure is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        mockkObject(PartitionMaintenance)
        coEvery {
            PartitionMaintenance.maintain(any(), any<PartitionPolicy>(), executor)
        } throws IllegalStateException("db down")

        scheduler.maintain()
    }
}
