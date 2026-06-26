// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.partition

import com.openbank.libs.persistence.partition.PartitionMaintenance
import com.openbank.libs.persistence.partition.PartitionPolicy
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
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
) {
    @Scheduled(every = "24h", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun maintain() {
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
            if (report.executed.isNotEmpty() || report.skippedDryRun.isNotEmpty() || report.defaultPartitionRows > 0) {
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
        } catch (ex: Exception) {
            // The scheduler must never crash; a failed pass is retried on the next tick.
            log.error("journal_entries partition maintenance failed", ex)
        }
    }

    companion object {
        private const val PARENT_TABLE = "journal_entries"
        private val log: Logger = Logger.getLogger(JournalPartitionMaintainer::class.java)
    }
}
