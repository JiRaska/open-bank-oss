// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PartitionManagerTest {

    private val today = LocalDate.of(2026, 5, 29)
    private val prefix = "journal_entries"
    private val parent = "journal_entries"

    @Test
    fun `partition naming and bounds follow the yearly convention`() {
        val p = PartitionManager.yearlyPartition(prefix, 2027)
        assertThat(p.name).isEqualTo("journal_entries_2027")
        assertThat(p.from).isEqualTo(LocalDate.of(2027, 1, 1))
        assertThat(p.toExclusive).isEqualTo(LocalDate.of(2028, 1, 1))
    }

    @Test
    fun `yearOf parses year from partition name and rejects non-matching names`() {
        assertThat(PartitionManager.yearOf(prefix, "journal_entries_2025")).isEqualTo(2025)
        assertThat(PartitionManager.yearOf(prefix, "journal_entries_default")).isNull()
        assertThat(PartitionManager.yearOf(prefix, "other_table_2025")).isNull()
    }

    @Test
    fun `createPartitionDdl is idempotent and uses half-open bounds`() {
        val ddl = PartitionManager.createPartitionDdl(parent, PartitionManager.yearlyPartition(prefix, 2027))
        assertThat(ddl).isEqualTo(
            "CREATE TABLE IF NOT EXISTS journal_entries_2027 PARTITION OF journal_entries " +
                "FOR VALUES FROM ('2027-01-01') TO ('2028-01-01')",
        )
    }

    @Test
    fun `roll-forward creates only the missing required years within the horizon`() {
        // existing: 2024..2026; horizon +2 from 2026 -> required 2026,2027,2028
        val plan = PartitionManager.planRollForward(
            today,
            prefix,
            parent,
            futureYears = 2,
            existingYears = setOf(2024, 2025, 2026),
        )
        assertThat(plan.map { it.partitionName })
            .containsExactly("journal_entries_2027", "journal_entries_2028")
        assertThat(plan).allMatch { it.action == PartitionAction.CREATE && it.ddl != null }
    }

    @Test
    fun `roll-forward is a no-op when the horizon is already covered`() {
        val plan = PartitionManager.planRollForward(
            today,
            prefix,
            parent,
            futureYears = 2,
            existingYears = setOf(2026, 2027, 2028),
        )
        assertThat(plan).isEmpty()
    }

    @Test
    fun `retention keeps exactly retentionYears calendar years and detaches older ones by default`() {
        // retention 10, today 2026 -> keep 2017..2026; expired: 2015, 2016
        val existing = (2015..2026).toSet()
        val plan = PartitionManager.planRetention(
            today,
            prefix,
            parent,
            retentionYears = 10,
            dropEnabled = false,
            existingYears = existing,
        )
        assertThat(plan.map { it.partitionName })
            .containsExactly("journal_entries_2015", "journal_entries_2016")
        assertThat(plan).allMatch { it.action == PartitionAction.DETACH }
        assertThat(plan.first().ddl)
            .isEqualTo("ALTER TABLE journal_entries DETACH PARTITION journal_entries_2015")
    }

    @Test
    fun `retention emits DROP statements only when dropEnabled`() {
        val existing = (2015..2026).toSet()
        val plan = PartitionManager.planRetention(
            today,
            prefix,
            parent,
            retentionYears = 10,
            dropEnabled = true,
            existingYears = existing,
        )
        assertThat(plan).allMatch { it.action == PartitionAction.DROP }
        assertThat(plan.first().ddl).isEqualTo("DROP TABLE IF EXISTS journal_entries_2015")
    }

    @Test
    fun `retention is a no-op when nothing is older than the window`() {
        val plan = PartitionManager.planRetention(
            today,
            prefix,
            parent,
            retentionYears = 10,
            dropEnabled = false,
            existingYears = (2024..2026).toSet(),
        )
        assertThat(plan).isEmpty()
    }
}
