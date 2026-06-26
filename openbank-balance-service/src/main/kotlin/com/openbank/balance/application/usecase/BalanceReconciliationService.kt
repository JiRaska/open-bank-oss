// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.LedgerControlBalancePort
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.ReconciliationPolicy
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * ADR-0039 Phase A — read-only control-account ⇄ sub-ledger reconciliation.
 *
 * Reads the ledger deposit-control balances and the balance-service booked sums, runs the pure
 * [ReconciliationPolicy] tie-out, persists the run for audit, and logs drift at WARN (the alerting
 * hook). It changes no balance — this is the safety net that detects divergence between the two
 * independent writers until Phases B–D make balance a true ledger projection.
 */
@ApplicationScoped
class BalanceReconciliationService(
    private val balanceRepo: BalanceRepository,
    private val ledgerControl: LedgerControlBalancePort,
    private val recordRepo: ReconciliationRecordRepository,
    private val clock: Clock,
) : ReconcileBalancesUseCase {

    private val log = Logger.getLogger(BalanceReconciliationService::class.java)

    override suspend fun reconcile(asOf: LocalDate): ReconciliationReport {
        val ledgerByCcy = ledgerControl.depositControlBalanceByCurrency(asOf)
        val bookedByCcy = balanceRepo.sumBookedByCurrency()

        val report = ReconciliationPolicy.reconcile(
            ledgerControlByCurrency = ledgerByCcy,
            subLedgerBookedByCurrency = bookedByCcy,
            asOf = asOf,
            generatedAt = OffsetDateTime.now(clock),
        )

        val persisted = recordRepo.save(report)

        if (report.hasDrift) {
            log.warnf(
                "Balance reconciliation DRIFT asOf=%s currencies=%s details=%s",
                asOf,
                report.driftedCurrencies,
                report.currencies.filter { !it.withinTolerance },
            )
        } else {
            log.infof(
                "Balance reconciliation OK asOf=%s currencies=%d",
                asOf,
                report.currencies.size,
            )
        }
        return persisted
    }

    override suspend fun latest(): ReconciliationReport? = recordRepo.findLatest()
}
