// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.partition

import java.time.LocalDate

/** One yearly RANGE partition spanning the half-open interval `[from, toExclusive)`. */
data class YearlyPartition(val year: Int, val name: String, val from: LocalDate, val toExclusive: LocalDate)

enum class PartitionAction { CREATE, DETACH, DROP, DEFAULT_NONEMPTY, NOOP }

/**
 * A single planned lifecycle action. [ddl] is the exact statement to execute, or `null` for
 * non-DDL signals (e.g. [PartitionAction.DEFAULT_NONEMPTY] which is purely an alert).
 */
data class PartitionPlanItem(
    val action: PartitionAction,
    val partitionName: String,
    val reason: String,
    val ddl: String?,
)

/**
 * Pure, side-effect-free helpers for managing yearly RANGE partitions of a Postgres table.
 *
 * There are deliberately no Quarkus / JDBC / reactive types here so the logic is trivially
 * unit-testable and reusable across any RANGE-partitioned table (journal_entries today;
 * audit events, psd2 request logs tomorrow). This mirrors ADR-0013: the generic mechanics
 * live in openbank-libs, while the `@Scheduled` CDI bean and the actual DB execution stay
 * service-side.
 *
 * Naming convention `"<prefix>_<year>"` matches the V1 ledger migration
 * (`journal_entries_2024`, ...). The DEFAULT partition is `"<prefix>_default"`.
 */
object PartitionManager {

    fun partitionName(prefix: String, year: Int): String = "${prefix}_$year"

    fun defaultPartitionName(prefix: String): String = "${prefix}_default"

    fun yearlyPartition(prefix: String, year: Int): YearlyPartition = YearlyPartition(
        year = year,
        name = partitionName(prefix, year),
        from = LocalDate.of(year, 1, 1),
        toExclusive = LocalDate.of(year + 1, 1, 1),
    )

    /** Extract the year from a `"<prefix>_<year>"` partition name, or `null` if it doesn't match. */
    fun yearOf(prefix: String, partitionName: String): Int? {
        val expected = "${prefix}_"
        if (!partitionName.startsWith(expected)) return null
        return partitionName.removePrefix(expected).toIntOrNull()
    }

    fun createPartitionDdl(parentTable: String, p: YearlyPartition): String =
        "CREATE TABLE IF NOT EXISTS ${p.name} PARTITION OF $parentTable " +
            "FOR VALUES FROM ('${p.from}') TO ('${p.toExclusive}')"

    /** DETACH is instant and non-destructive: the partition becomes a standalone, still-queryable table. */
    fun detachPartitionDdl(parentTable: String, partitionName: String): String =
        "ALTER TABLE $parentTable DETACH PARTITION $partitionName"

    fun dropPartitionDdl(partitionName: String): String = "DROP TABLE IF EXISTS $partitionName"

    /** Years that must have a live partition as of [today]: currentYear .. currentYear + [futureYears]. */
    fun requiredYears(today: LocalDate, futureYears: Int): List<Int> = (today.year..today.year + futureYears).toList()

    /**
     * CREATE plan: required years (current + horizon) that have no existing partition yet.
     * Always safe and idempotent (`CREATE TABLE IF NOT EXISTS`).
     */
    fun planRollForward(
        today: LocalDate,
        prefix: String,
        parentTable: String,
        futureYears: Int,
        existingYears: Set<Int>,
    ): List<PartitionPlanItem> = requiredYears(today, futureYears)
        .filter { it !in existingYears }
        .map { year ->
            val p = yearlyPartition(prefix, year)
            PartitionPlanItem(
                action = PartitionAction.CREATE,
                partitionName = p.name,
                reason = "roll-forward: ensure partition for year $year (horizon=+$futureYears)",
                ddl = createPartitionDdl(parentTable, p),
            )
        }

    /**
     * Retention plan: existing yearly partitions older than the retention window.
     *
     * With [retentionYears] = 10 and today in 2026 we keep 2017..2026 (10 calendar years) active;
     * anything with `year < today.year - retentionYears + 1` is expired. Expired partitions get
     * DETACH (non-destructive) unless [dropEnabled], in which case DROP. The list is sorted oldest
     * first so audit/log output reads chronologically.
     */
    fun planRetention(
        today: LocalDate,
        prefix: String,
        parentTable: String,
        retentionYears: Int,
        dropEnabled: Boolean,
        existingYears: Set<Int>,
    ): List<PartitionPlanItem> {
        val firstYearToKeep = today.year - retentionYears + 1
        return existingYears.filter { it < firstYearToKeep }.sorted().map { year ->
            val p = yearlyPartition(prefix, year)
            if (dropEnabled) {
                PartitionPlanItem(
                    action = PartitionAction.DROP,
                    partitionName = p.name,
                    reason = "retention: year $year is older than the $retentionYears-year window " +
                        "(keep >= $firstYearToKeep); physical drop (dropEnabled=true)",
                    ddl = dropPartitionDdl(p.name),
                )
            } else {
                PartitionPlanItem(
                    action = PartitionAction.DETACH,
                    partitionName = p.name,
                    reason = "retention: year $year is older than the $retentionYears-year window " +
                        "(keep >= $firstYearToKeep); detach (non-destructive, archive then drop manually)",
                    ddl = detachPartitionDdl(parentTable, p.name),
                )
            }
        }
    }
}
