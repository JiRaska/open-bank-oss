// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
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
import java.time.LocalDate

/**
 * ADR-0039 Phase A — daily control-account ⇄ sub-ledger reconciliation. Runs the read-only tie-out
 * after the business day, persisting the result and logging drift. The use-case mutates no balance;
 * the scheduler only triggers it and must never crash the runtime, so all failures are swallowed
 * after logging.
 *
 * **Cross-pod exclusion (#1201).** `concurrentExecution = SKIP` only stops in-JVM overlap; an
 * Argo Rollouts canary window runs the old and new pod simultaneously for the whole rollout, and
 * both fire this trigger on their own tick. `reconcile.reconcile()` persists a
 * [com.openbank.balance.domain.reconciliation.ReconciliationReport] row and records the drift
 * gauge on every call — two pods both firing at 23:30 would write two report rows for the same
 * `asOf` and double-record the metric, not corrupt any balance (the use-case itself mutates
 * nothing), but it is exactly the "no per-row claim to make, only one pod's tick should run at
 * all" shape [ClusterLock.tryRunExclusively] exists for.
 */
@ApplicationScoped
class BalanceReconciliationScheduler(
    private val reconcile: ReconcileBalancesUseCase,
    private val clock: Clock,
    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(BalanceReconciliationScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(
        cron = "{openbank.reconciliation.cron:0 30 23 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runDaily() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val report = reconcile.reconcile(LocalDate.now(clock))
                if (report.hasDrift) {
                    log.warnf("Daily balance reconciliation found drift: %s", report.driftedCurrencies)
                }
                liveness?.recordSuccess()
            } catch (ex: Exception) {
                log.errorf(ex, "Daily balance reconciliation failed: %s", ex.message)
            }
        }
        if (ran == null) {
            log.infof("Daily balance reconciliation: another pod already holds this tick's lock — skipping")
        }
    }

    private companion object {
        const val JOB_NAME = "balance.reconciliation"
        const val WORKFLOW_NAME = "balance-reconciliation"
    }
}
