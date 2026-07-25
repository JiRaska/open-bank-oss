// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.usecase

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.LedgerControlBalancePort
import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.ReconciliationPolicy
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.libs.observability.DomainMetrics
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
 *
 * Also publishes each currency's drift via [DomainMetrics.recordReconciliationDrift] (ADR-0160
 * mechanism 4): a `PrometheusRule` with a `for:` clause pages only once drift has been sustained
 * across consecutive runs, not on a single snapshot — a transient snapshot taken mid-backfill was
 * previously misread as a ~220k CZK integrity crisis (issue #860) before this existed.
 */
@ApplicationScoped
class BalanceReconciliationService(
    private val balanceRepo: BalanceRepository,
    private val ledgerControl: LedgerControlBalancePort,
    private val recordRepo: ReconciliationRecordRepository,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) : ReconcileBalancesUseCase {

    private val log = Logger.getLogger(BalanceReconciliationService::class.java)

    override suspend fun reconcile(asOf: LocalDate): ReconciliationReport {
        val ledgerByCcy = ledgerControl.depositControlBalanceByCurrency(asOf)
        // Value-date basis, matching the ledger trial balance's `entry_date <= asOf` (ADR-0178):
        // a future-value-dated journal is POSTED but not yet in the ledger control, so counting it in
        // the sub-ledger sum would surface a self-resolving false drift for the whole pre-value window.
        val bookedByCcy = balanceRepo.sumBookedByCurrencyAsOf(asOf)
        // ADR-0178 Phase 3 — explainability. The tail the line above subtracts, reported in its own
        // right: POSTED movements whose value date is still ahead, which neither side of the tie-out
        // counts yet. Attached to the report, never to `difference` — both sides already exclude it,
        // so folding it into the drift would double-count and re-introduce the very false positive
        // Phase 1 removed. Drift therefore stays UNEXPLAINED drift; this column is the explained part.
        val futureValueDatedByCcy = balanceRepo.sumFutureValueDatedByCurrency(asOf)

        val report = ReconciliationPolicy.reconcile(
            ledgerControlByCurrency = ledgerByCcy,
            subLedgerBookedByCurrency = bookedByCcy,
            asOf = asOf,
            generatedAt = OffsetDateTime.now(clock),
            futureValueDatedByCurrency = futureValueDatedByCcy,
        )

        val persisted = recordRepo.save(report)

        // Recorded for every currency, including within-tolerance ones (drift = 0) — the gauge
        // must reflect the CURRENT state, not freeze at the last non-zero reading.
        report.currencies.forEach {
            domainMetrics.recordReconciliationDrift(CONTROL_NAME, it.currency, it.difference)
        }

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

    private companion object {
        const val CONTROL_NAME = "balance_deposit_control"
    }
}
