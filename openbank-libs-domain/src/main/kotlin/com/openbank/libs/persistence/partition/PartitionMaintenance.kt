// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.partition

import java.time.LocalDate

/** Immutable record of one lifecycle action, persisted to the service's audit table. */
data class PartitionAuditRecord(
    val parentTable: String,
    val partitionName: String,
    val action: PartitionAction,
    val reason: String,
    val dryRun: Boolean,
)

/**
 * Port the service implements with its reactive DB client. Mirrors the outbox `OutboxRepository`
 * port: all Postgres-specific SQL lives in the service-side adapter, the orchestrator below stays
 * DB-agnostic and unit-testable.
 */
interface PartitionExecutor {
    /** Names of every child partition of [parentTable] (including the DEFAULT partition). */
    suspend fun listChildPartitions(parentTable: String): List<String>

    /** Row count of [table]; used for the default-partition guard. */
    suspend fun rowCount(table: String): Long

    /** Execute a single DDL statement (CREATE / DETACH / DROP). */
    suspend fun executeDdl(ddl: String)

    /** Append one row to the immutable partition lifecycle audit log. */
    suspend fun recordAudit(record: PartitionAuditRecord)
}

data class PartitionPolicy(
    val parentTable: String,
    val prefix: String,
    /** Pre-create this many years beyond the current year. */
    val futureYears: Int = 2,
    /** Keep at least this many calendar years of partitions attached. */
    val retentionYears: Int = 10,
    /** When true, expired partitions are physically dropped instead of merely detached. */
    val dropEnabled: Boolean = false,
    /** When true (default), structural actions (DETACH/DROP) are only audited, not executed. */
    val dryRun: Boolean = true,
)

data class PartitionMaintenanceReport(
    val executed: List<PartitionPlanItem>,
    val skippedDryRun: List<PartitionPlanItem>,
    val defaultPartitionRows: Long,
)

/**
 * Orchestrates one maintenance pass. Analogous to `OutboxDispatch` (openbank-libs-runtime):
 * the service annotates a `@Scheduled` method that calls [maintain], injecting its own
 * [PartitionExecutor]. No CDI, no reactive types here.
 *
 * Safety model:
 *  - CREATE (roll-forward) is idempotent and always executed, even in dry-run, so the partition
 *    horizon can never silently lapse and dump rows into the DEFAULT partition.
 *  - DETACH / DROP respect [PartitionPolicy.dryRun]: in dry-run they are audited but not executed.
 *  - Every action — executed or dry-run — is written to the audit log before/after execution.
 */
object PartitionMaintenance {

    suspend fun maintain(
        today: LocalDate,
        policy: PartitionPolicy,
        executor: PartitionExecutor,
    ): PartitionMaintenanceReport {
        val children = executor.listChildPartitions(policy.parentTable)
        val existingYears = children.mapNotNull { PartitionManager.yearOf(policy.prefix, it) }.toSet()

        val plan = buildList {
            addAll(
                PartitionManager.planRollForward(
                    today,
                    policy.prefix,
                    policy.parentTable,
                    policy.futureYears,
                    existingYears,
                ),
            )
            addAll(
                PartitionManager.planRetention(
                    today,
                    policy.prefix,
                    policy.parentTable,
                    policy.retentionYears,
                    policy.dropEnabled,
                    existingYears,
                ),
            )
        }

        val executed = mutableListOf<PartitionPlanItem>()
        val skipped = mutableListOf<PartitionPlanItem>()

        for (item in plan) {
            val alwaysSafe = item.action == PartitionAction.CREATE
            if (policy.dryRun && !alwaysSafe) {
                executor.recordAudit(item.toAudit(policy.parentTable, dryRun = true))
                skipped += item
            } else {
                item.ddl?.let { executor.executeDdl(it) }
                executor.recordAudit(item.toAudit(policy.parentTable, dryRun = false))
                executed += item
            }
        }

        // Default-partition guard: a non-empty default partition means inserts are being misrouted
        // (horizon gap, or an entry_date outside every declared range). Surface it loudly + audit.
        val defaultName = PartitionManager.defaultPartitionName(policy.prefix)
        val defaultRows = runCatching { executor.rowCount(defaultName) }.getOrDefault(0L)
        if (defaultRows > 0) {
            executor.recordAudit(
                PartitionAuditRecord(
                    parentTable = policy.parentTable,
                    partitionName = defaultName,
                    action = PartitionAction.DEFAULT_NONEMPTY,
                    reason = "default partition holds $defaultRows row(s) — inserts misrouted; investigate horizon",
                    dryRun = false,
                ),
            )
        }

        return PartitionMaintenanceReport(executed, skipped, defaultRows)
    }

    private fun PartitionPlanItem.toAudit(parentTable: String, dryRun: Boolean) = PartitionAuditRecord(
        parentTable = parentTable,
        partitionName = partitionName,
        action = action,
        reason = reason,
        dryRun = dryRun,
    )
}
