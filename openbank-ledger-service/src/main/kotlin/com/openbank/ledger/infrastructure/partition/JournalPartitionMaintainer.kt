// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.partition

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import com.openbank.libs.persistence.partition.PartitionMaintenance
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
 * Scheduled roll-forward + retention for the RANGE-partitioned `journal_entries` table.
 *
 * Mirrors the outbox dispatcher pattern (ADR-0013): the `@Scheduled` CDI bean lives in the
 * service and delegates the actual logic to the shared, unit-tested
 * [com.openbank.libs.persistence.partition.PartitionMaintenance] orchestrator.
 *
 * Safety:
 *  - Roll-forward CREATE is idempotent and always runs, so the partition horizon never lapses.
 *  - Retention is DETACH-only and dry-run by default; physical DROP requires explicit config.
 *  - Every action is recorded in the immutable `partition_lifecycle_audit` table.
 *  - Cross-pod exclusion (#1201): an Argo Rollouts canary window runs two pods, and both fire
 *    `@Scheduled` beans on their own tick — without coordination, two pods could race the same
 *    partition CREATE/DETACH/DROP DDL. [ClusterLock.tryRunExclusively] wraps the run in a
 *    transaction-scoped advisory lock so only one pod's tick executes.
 */
@ApplicationScoped
class JournalPartitionMaintainer(
    private val clock: Clock,
    private val executor: HibernatePartitionExecutor,

    @ConfigProperty(name = "openbank.ledger.partition.future-years", defaultValue = "2")
    private val futureYears: Int,

    @ConfigProperty(name = "openbank.ledger.partition.retention-years", defaultValue = "10")
    private val retentionYears: Int,

    @ConfigProperty(name = "openbank.ledger.partition.drop-enabled", defaultValue = "false")
    private val dropEnabled: Boolean,

    @ConfigProperty(name = "openbank.ledger.partition.dry-run", defaultValue = "true")
    private val dryRun: Boolean,

    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(every = "24h", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun maintain() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val policy = PartitionPolicy(
                    parentTable = PARENT_TABLE,
                    prefix = PARENT_TABLE,
                    futureYears = futureYears,
                    retentionYears = retentionYears,
                    dropEnabled = dropEnabled,
                    dryRun = dryRun,
                )
                val report = PartitionMaintenance.maintain(LocalDate.now(clock), policy, executor)
                if (report.executed.isNotEmpty() ||
                    report.skippedDryRun.isNotEmpty() ||
                    report.defaultPartitionRows > 0
                ) {
                    log.infof(
                        "journal_entries partition maintenance: executed=%d, dryRunSkipped=%d, defaultRows=%d",
                        report.executed.size,
                        report.skippedDryRun.size,
                        report.defaultPartitionRows,
                    )
                }
                if (report.defaultPartitionRows > 0) {
                    log.warnf(
                        "journal_entries_default holds %d row(s) — inserts are being misrouted; check the partition horizon",
                        report.defaultPartitionRows,
                    )
                }
                liveness?.recordSuccess()
            } catch (ex: Exception) {
                // The scheduler must never crash; a failed pass is retried on the next tick.
                log.error("journal_entries partition maintenance failed", ex)
            }
        }
        if (ran == null) {
            log.infof("journal_entries partition maintenance: another pod already holds this tick's lock — skipping")
        }
    }

    private companion object {
        private const val PARENT_TABLE = "journal_entries"
        private const val JOB_NAME = "ledger.partition-maintenance"
        private const val WORKFLOW_NAME = "ledger-journal-partition-maintenance"
        private val log: Logger = Logger.getLogger(JournalPartitionMaintainer::class.java)
    }
}
