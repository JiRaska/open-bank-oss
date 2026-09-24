// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.time.LocalDate

/** Bucketed flows of one currency; [presentValue] is null when the set has no curve for it. */
data class CurrencyCashFlows(
    val currency: String,
    val discountIndex: CurveIndex?,
    val positions: Int,
    val buckets: Map<TimeBucket, BigDecimal>,
    val total: BigDecimal,
    val presentValue: BigDecimal?,
) {
    val priced: Boolean get() = presentValue != null
}

data class SnapshotCashFlows(
    val model: BehaviouralModel,
    val expanded: Int,
    val notExpanded: Int,
    val currencies: List<CurrencyCashFlows>,
) {
    val unpriced: List<String> get() = currencies.filterNot { it.priced }.map { it.currency }
}

/**
 * Expands a tied-out snapshot into bucketed, discounted flows (ADR-0314 D6, phase 0).
 *
 * Only [PositionKind.SUB_LEDGER] positions — customer balances on deposit control — are expanded,
 * each as a non-maturity deposit. Positions are in the trial-balance convention (debit − credit),
 * so a customer's credit balance is negative and the bank owes `−amount`; the flows are outflows
 * summing to exactly the position amounts.
 *
 * GL_ACCOUNT positions are COUNTED in [SnapshotCashFlows.notExpanded], never silently dropped: the
 * ledger gives no contract behind them. Loans are absent for the same reason — the snapshot holds
 * no loan contracts until the instrument-model PR (ADR-0314 D4).
 *
 * A currency without a discounting curve in the set keeps its buckets and gets a null PV — it is
 * reported unpriced, never valued at zero.
 */
object SnapshotCashFlowProjection {

    fun project(
        positions: List<Position>,
        asOf: LocalDate,
        curves: CurveSet,
        model: BehaviouralModel,
    ): SnapshotCashFlows {
        val deposits = positions.filter { it.kind == PositionKind.SUB_LEDGER }
        val currencies = deposits.groupBy { it.currency }.toSortedMap().map { (currency, ps) ->
            val flows = ps.flatMap { NonMaturityDepositCashFlows.expand(it.amount.negate(), currency, asOf, model) }
            val curve = curves.discountCurveFor(currency)
            CurrencyCashFlows(
                currency = currency,
                discountIndex = curve?.index,
                positions = ps.size,
                buckets = CashFlowAggregation.bucket(flows, asOf),
                total = flows.fold(BigDecimal.ZERO) { acc, f -> acc.add(f.amount) },
                presentValue = curve?.let { CashFlowAggregation.presentValue(flows, it, minorUnits(currency)) },
            )
        }
        return SnapshotCashFlows(model, deposits.size, positions.size - deposits.size, currencies)
    }
}
