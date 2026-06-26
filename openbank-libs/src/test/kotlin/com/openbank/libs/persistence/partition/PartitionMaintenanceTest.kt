// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.partition

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PartitionMaintenanceTest {

    private val today = LocalDate.of(2026, 5, 29)

    /** In-memory executor recording DDL + audit, with a configurable child-partition set. */
    private class FakeExecutor(children: List<String>, private val defaultRows: Long = 0) : PartitionExecutor {
        val existing = children.toMutableList()
        val ddls = mutableListOf<String>()
        val audits = mutableListOf<PartitionAuditRecord>()

        override suspend fun listChildPartitions(parentTable: String): List<String> = existing.toList()
        override suspend fun rowCount(table: String): Long = defaultRows
        override suspend fun executeDdl(ddl: String) {
            ddls += ddl
        }
        override suspend fun recordAudit(record: PartitionAuditRecord) {
            audits += record
        }
    }

    @Test
    fun `dry-run executes safe CREATE but only audits DETACH`(): Unit = runBlocking {
        val executor = FakeExecutor(children = (2015..2026).map { "journal_entries_$it" } + "journal_entries_default")
        val policy = PartitionPolicy(
            parentTable = "journal_entries",
            prefix = "journal_entries",
            futureYears = 2,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = true,
        )

        val report = PartitionMaintenance.maintain(today, policy, executor)

        // CREATE 2027/2028 actually executed even in dry-run; DETACH 2015/2016 only planned.
        assertThat(executor.ddls).containsExactlyInAnyOrder(
            "CREATE TABLE IF NOT EXISTS journal_entries_2027 PARTITION OF journal_entries " +
                "FOR VALUES FROM ('2027-01-01') TO ('2028-01-01')",
            "CREATE TABLE IF NOT EXISTS journal_entries_2028 PARTITION OF journal_entries " +
                "FOR VALUES FROM ('2028-01-01') TO ('2029-01-01')",
        )
        assertThat(report.executed).allMatch { it.action == PartitionAction.CREATE }
        assertThat(report.skippedDryRun.map { it.partitionName })
            .containsExactly("journal_entries_2015", "journal_entries_2016")
        // Every action is audited regardless of dry-run.
        assertThat(executor.audits.filter { it.action == PartitionAction.DETACH })
            .allMatch { it.dryRun }
    }

    @Test
    fun `non-dry-run executes detach DDL for expired partitions`(): Unit = runBlocking {
        val executor = FakeExecutor(children = (2015..2026).map { "journal_entries_$it" })
        val policy = PartitionPolicy(
            parentTable = "journal_entries",
            prefix = "journal_entries",
            futureYears = 0,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = false,
        )

        PartitionMaintenance.maintain(today, policy, executor)

        assertThat(executor.ddls).contains(
            "ALTER TABLE journal_entries DETACH PARTITION journal_entries_2015",
            "ALTER TABLE journal_entries DETACH PARTITION journal_entries_2016",
        )
    }

    @Test
    fun `non-empty default partition raises an audited guard alert`(): Unit = runBlocking {
        val executor = FakeExecutor(
            children = listOf("journal_entries_2026", "journal_entries_default"),
            defaultRows = 7,
        )
        val policy = PartitionPolicy(
            parentTable = "journal_entries",
            prefix = "journal_entries",
            futureYears = 0,
            retentionYears = 10,
            dropEnabled = false,
            dryRun = true,
        )

        val report = PartitionMaintenance.maintain(today, policy, executor)

        assertThat(report.defaultPartitionRows).isEqualTo(7)
        assertThat(executor.audits).anyMatch {
            it.action == PartitionAction.DEFAULT_NONEMPTY && !it.dryRun
        }
    }
}
