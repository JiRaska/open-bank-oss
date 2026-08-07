// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.out.ReconciliationRecordRepository
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
 * Freshness watchdog for the daily control-account ⇄ sub-ledger reconciliation (ADR-0039 Phase A).
 *
 * The daily tie-out ([BalanceReconciliationScheduler] @ 23:30) is an audit-critical control, and it
 * can fail SILENTLY: [com.openbank.balance.application.usecase.BalanceReconciliationService] persists
 * only on success and the scheduler swallows exceptions after logging, so a broken run leaves no
 * record and no alarm. That is exactly how a missing id sequence let the tie-out persist ZERO rows
 * for 41 days unnoticed (issue #855).
 *
 * This watchdog runs hourly and escalates the ABSENCE of a fresh tie-out to ERROR, so the log-based
 * alerting stack (Alloy → Loki) pages instead of the gap sitting silent — a missed daily control is
 * now loud. It is read-only and never touches a balance.
 *
 * **Cross-pod exclusion (#1201).** Read-only and idempotent, so two pods both checking is not a
 * correctness bug — but during every canary window it would otherwise double every log line and
 * double-fire the Loki alert for the same incident. [ClusterLock.tryRunExclusively] wraps the
 * check so only one pod's tick actually logs.
 */
@ApplicationScoped
class ReconciliationFreshnessWatchdog(
    private val recordRepo: ReconciliationRecordRepository,
    private val clock: Clock,
    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(ReconciliationFreshnessWatchdog::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    // The daily run fires at 23:30, so a healthy tie-out is at most ~24h old; allow a 1h grace
    // for run duration or a delayed scheduler before we call it a missed control.
    private val staleAfter = Duration.ofHours(DAILY_SLA_HOURS)

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.reconciliation.freshness-cron:0 20 * * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun checkFreshness() {
        clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val latest = recordRepo.findLatest()
                if (latest == null) {
                    log.error(
                        "Balance reconciliation freshness: NO tie-out has ever succeeded — the daily " +
                            "control-account ⇄ sub-ledger reconciliation (ADR-0039) is absent. Investigate.",
                    )
                } else {
                    val ageHours = Duration.between(latest.generatedAt.toInstant(), Instant.now(clock)).toHours()
                    if (ageHours > staleAfter.toHours()) {
                        log.errorf(
                            "Balance reconciliation freshness: STALE — last successful tie-out was %dh ago " +
                                "(as-of %s), past the %dh daily SLA; a scheduled run was likely missed.",
                            ageHours,
                            latest.asOf,
                            staleAfter.toHours(),
                        )
                    } else {
                        log.debugf(
                            "Balance reconciliation freshness OK: last tie-out %dh ago (as-of %s).",
                            ageHours,
                            latest.asOf,
                        )
                    }
                }
                liveness?.recordSuccess()
            } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                log.errorf(ex, "Balance reconciliation freshness check failed: %s", ex.message)
            }
        }
    }

    private companion object {
        // 24h daily cadence + 1h grace for run duration / a delayed scheduler.
        const val DAILY_SLA_HOURS = 25L
        const val JOB_NAME = "balance.reconciliation.freshness"
        const val WORKFLOW_NAME = "balance-reconciliation-freshness"
    }
}
