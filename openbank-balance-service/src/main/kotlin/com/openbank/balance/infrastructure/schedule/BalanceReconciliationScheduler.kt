// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-0039 Phase A — daily control-account ⇄ sub-ledger reconciliation. Runs the read-only tie-out
 * after the business day, persisting the result and logging drift. The use-case mutates no balance;
 * the scheduler only triggers it and must never crash the runtime, so all failures are swallowed
 * after logging.
 */
@ApplicationScoped
class BalanceReconciliationScheduler(private val reconcile: ReconcileBalancesUseCase, private val clock: Clock) {
    private val log = Logger.getLogger(BalanceReconciliationScheduler::class.java)

    @Scheduled(
        cron = "{openbank.reconciliation.cron:0 30 23 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runDaily() {
        try {
            val report = reconcile.reconcile(LocalDate.now(clock))
            if (report.hasDrift) {
                log.warnf("Daily balance reconciliation found drift: %s", report.driftedCurrencies)
            }
        } catch (ex: Exception) {
            log.errorf(ex, "Daily balance reconciliation failed: %s", ex.message)
        }
    }
}
