// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.partition

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionMaintenanceReport
import com.openbank.libs.persistence.partition.PartitionPolicy
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Scheduled roll-forward for the RANGE-partitioned `transactions` table (#17101), mirroring the
 * ledger's `JournalPartitionMaintainer`: the shared, unit-tested [PartitionMaintenance]
 * orchestrator does the planning, [HibernatePartitionExecutor] runs the DDL.
 *
 *  - Roll-forward CREATE is idempotent and always runs, so the horizon (current year +
 *    `future-years`) never lapses and rows never fall into `transactions_default`.
 *  - Retention is deliberately NOT managed here: the policy is forced to dry-run, so an expired
 *    year is only audited, never detached or dropped.
 *  - Every action lands in the append-only `partition_lifecycle_audit` table (V25).
 *  - Cross-pod exclusion via [ClusterLock] — a canary window runs two pods.
 *  - Liveness: `ledger`'s pattern — the generic `WorkflowLivenessStale` rule fires if no
 *    successful pass is recorded for 2x the daily interval, so a dead maintainer is visible
 *    long before the horizon actually runs out.
 *
 * `suspend fun` on purpose: a plain `@Scheduled` method carries no Vert.x context and the
 * reactive DDL would fail with HR000068 on every tick (`rules.yaml: scheduled_methods`).
 */
@ApplicationScoped
class TransactionPartitionMaintainer(
    private val clock: Clock,
    private val executor: HibernatePartitionExecutor,

    @ConfigProperty(name = "openbank.transaction.partition.future-years", defaultValue = "2")
    private val futureYears: Int,

    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {

    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(
        every = "{openbank.transaction.partition.every}",
        delayed = "{openbank.transaction.partition.delayed}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // Broad catch is the contract: any failure (DDL, lock, connection) must leave the scheduler
    // alive for the next tick, and the unrecorded success is what pages.
    @Suppress("TooGenericExceptionCaught")
    suspend fun maintain() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val report = PartitionMaintenance.maintain(LocalDate.now(clock), policy(), executor)
                logReport(report)
                liveness?.recordSuccess()
            } catch (ex: Exception) {
                // The scheduler must never crash; a failed pass is retried on the next tick and
                // the missing success is what the liveness alert reads.
                log.error("transactions partition maintenance failed", ex)
            }
        }
        if (ran == null) {
            log.infof("transactions partition maintenance: another pod already holds this tick's lock — skipping")
        }
    }

    private fun policy() = PartitionPolicy(
        parentTable = PARENT_TABLE,
        prefix = PARENT_TABLE,
        futureYears = futureYears,
        retentionYears = RETENTION_YEARS,
        dropEnabled = false,
        dryRun = true,
    )

    private fun logReport(report: PartitionMaintenanceReport) {
        if (report.executed.isNotEmpty() || report.defaultPartitionRows > 0) {
            log.infof(
                "transactions partition maintenance: executed=%d, defaultRows=%d",
                report.executed.size,
                report.defaultPartitionRows,
            )
        }
        if (report.defaultPartitionRows > 0) {
            log.warnf(
                "transactions_default holds %d row(s) — inserts are being misrouted; check the partition horizon",
                report.defaultPartitionRows,
            )
        }
    }

    private companion object {
        private const val PARENT_TABLE = "transactions"
        private const val JOB_NAME = "transaction.partition-maintenance"
        const val WORKFLOW_NAME = "transaction-partition-maintenance"

        // Audited only (policy is dry-run); matches the 10-year record-keeping horizon ledger uses.
        private const val RETENTION_YEARS = 10
        private val log: Logger = Logger.getLogger(TransactionPartitionMaintainer::class.java)
    }
}
