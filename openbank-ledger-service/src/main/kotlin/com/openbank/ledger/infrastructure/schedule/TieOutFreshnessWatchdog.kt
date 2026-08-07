// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Freshness watchdog for the daily sub-ledger tie-out ([TieOutScheduler] @ 06:00).
 *
 * The tie-out is an audit-critical control (ADR-0039 Phase B) whose scheduler deliberately
 * swallows failures so it never crashes — which means a broken run can be SILENT. That is the
 * exact failure mode balance-service hit in issue #855 (41 days of missing reconciliation,
 * unnoticed), and this watchdog is the same countermeasure applied to the ledger side:
 * hourly, escalate the ABSENCE of a fresh successful run to ERROR so the log-based alerting
 * stack (Alloy → Loki) pages. Breaks themselves page via the SubledgerTieOutBreak
 * PrometheusRule on the break counter; this watchdog covers the two cases the counter
 * cannot: the run not happening at all, and the run erroring before it could check.
 * Read-only; never touches the journal.
 *
 * Cross-pod exclusion (#1201): read-only and idempotent, so two pods both checking is not a
 * correctness bug — but during every canary window it would otherwise double every log line and
 * double-fire the Loki alert for the same incident. [ClusterLock.tryRunExclusively] wraps the
 * check so only one pod's tick actually logs.
 */
@ApplicationScoped
class TieOutFreshnessWatchdog(
    private val runRepository: TieOutRunRepository,
    private val clock: Clock,
    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(TieOutFreshnessWatchdog::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    // The daily run fires at 06:00, so a healthy record is at most ~24h old; allow a 1h grace
    // for run duration or a delayed scheduler before we call it a missed control.
    private val staleAfter = Duration.ofHours(DAILY_SLA_HOURS)

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.ledger.tieout.freshness-cron:0 40 * * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun checkFreshness() {
        clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val latest = runRepository.findLatest()
                if (latest == null) {
                    log.error(
                        "Tie-out freshness: NO tie-out run has ever been recorded — the daily " +
                            "GL control-account ⇄ sub-ledger tie-out (ADR-0039 Phase B) is absent. Investigate.",
                    )
                } else {
                    val ageHours = Duration.between(latest.runAt, Instant.now(clock)).toHours()
                    when {
                        ageHours > staleAfter.toHours() -> log.errorf(
                            "Tie-out freshness: STALE — last run was %dh ago (as-of %s, status %s), past the " +
                                "%dh daily SLA; a scheduled run was likely missed.",
                            ageHours,
                            latest.asOf,
                            latest.status,
                            staleAfter.toHours(),
                        )
                        latest.status == TieOutRunStatus.ERROR -> log.errorf(
                            "Tie-out freshness: last run (as-of %s) ended in ERROR — %d of %d control-account " +
                                "checks failed; the day's control is incomplete. Investigate and re-run.",
                            latest.asOf,
                            latest.errors,
                            latest.errors + latest.accountsChecked,
                        )
                        else -> log.debugf(
                            "Tie-out freshness OK: last run %dh ago (as-of %s, status %s).",
                            ageHours,
                            latest.asOf,
                            latest.status,
                        )
                    }
                }
                liveness?.recordSuccess()
            } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                log.errorf(ex, "Tie-out freshness check failed: %s", ex.message)
            }
        }
    }

    private companion object {
        // 24h daily cadence + 1h grace for run duration / a delayed scheduler.
        const val DAILY_SLA_HOURS = 25L
        const val JOB_NAME = "ledger.tieout.freshness"
        const val WORKFLOW_NAME = "ledger-tieout-freshness"
    }
}
