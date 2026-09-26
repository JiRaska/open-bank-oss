// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.RateType
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
 * Expands a tied-out snapshot into bucketed, discounted flows (ADR-0314 D4, D6).
 *
 * Expanded:
 *  - [PositionKind.SUB_LEDGER] positions — customer balances on deposit control — each as a
 *    non-maturity deposit. Positions are in the trial-balance convention (debit − credit), so a
 *    customer's credit balance is negative and the bank owes `−amount`; the flows are OUTFLOWS
 *    summing to exactly the position amounts;
 *  - loan instruments ([InstrumentKind.AMORTISING_LOAN], [InstrumentKind.BULLET]) through
 *    [AmortisingLoanCashFlows] — assets, so INFLOWS. FIXED emits lending's own remaining
 *    installments; FLOATING projects from the run's curve for its index with its reset terms.
 *    A loan with nothing outstanding is expanded to no flows.
 *
 * GL_ACCOUNT positions are COUNTED in [SnapshotCashFlows.notExpanded], never silently dropped: the
 * ledger gives no contract behind them. Once loans are on the snapshot, Loans Receivable is no
 * longer among them.
 *
 * A currency without a discounting curve in the set keeps its buckets and gets a null PV — it is
 * reported unpriced, never valued at zero. A FLOATING loan whose index has no curve in the set
 * cannot be projected at all, which is a 400 for the caller (choose a complete curve set), not a
 * silently omitted loan.
 */
object SnapshotCashFlowProjection {

    internal val LOAN_KINDS = setOf(InstrumentKind.AMORTISING_LOAN, InstrumentKind.BULLET)

    fun project(
        positions: List<Position>,
        asOf: LocalDate,
        curves: CurveSet,
        model: BehaviouralModel,
        instruments: List<Instrument> = emptyList(),
    ): SnapshotCashFlows {
        val deposits = positions.filter { it.kind == PositionKind.SUB_LEDGER }
        val loans = instruments.filter { it.kind in LOAN_KINDS }
        val depositFlows = deposits.groupBy { it.currency }.mapValues { (currency, ps) ->
            ps.flatMap { NonMaturityDepositCashFlows.expand(it.amount.negate(), currency, asOf, model) }
        }
        val loanFlows = loans.groupBy { it.currency }.mapValues { (_, ls) -> ls.flatMap { loanFlows(it, curves) } }
        val counts = (deposits.map { it.currency } + loans.map { it.currency }).groupingBy { it }.eachCount()
        val currencies = counts.keys.sorted().map { currency ->
            val flows = depositFlows[currency].orEmpty() + loanFlows[currency].orEmpty()
            val curve = curves.discountCurveFor(currency)
            CurrencyCashFlows(
                currency = currency,
                discountIndex = curve?.index,
                positions = counts.getValue(currency),
                buckets = CashFlowAggregation.bucket(flows, asOf),
                total = flows.fold(BigDecimal.ZERO) { acc, f -> acc.add(f.amount) },
                presentValue = curve?.let { CashFlowAggregation.presentValue(flows, it, minorUnits(currency)) },
            )
        }
        val loanPositions = positions.count { it.kind == PositionKind.LOAN }
        return SnapshotCashFlows(
            model = model,
            expanded = deposits.size + loans.size,
            notExpanded = positions.size - deposits.size - loanPositions,
            currencies = currencies,
        )
    }

    /** The rate terms of a loan instrument as the cash-flow engine needs them. */
    internal fun loanRate(instrument: Instrument): LoanRate {
        val terms = requireNotNull(instrument.rateTerms) { "loan ${instrument.id} has no rate terms" }
        return when (terms.rateType) {
            RateType.FIXED -> LoanRate.Fixed(requireNotNull(terms.currentAnnualRate) { "fixed loan without a rate" })
            RateType.FLOATING -> LoanRate.Floating(
                index = requireNotNull(terms.index) { "floating loan ${instrument.id} without an index" },
                spread = requireNotNull(terms.spread) { "floating loan ${instrument.id} without a spread" },
                currentRate = terms.currentAnnualRate,
                resetFrequencyMonths = terms.resetFrequencyMonths,
                nextResetDate = terms.nextResetDate,
            )
        }
    }

    internal fun loanFlows(instrument: Instrument, curves: CurveSet): List<CashFlow> {
        if (instrument.outstanding.signum() == 0) return emptyList()
        val ext = requireNotNull(instrument.extension as? LoanExtension) { "loan ${instrument.id} has no loan terms" }
        val rate = loanRate(instrument)
        val curve = (rate as? LoanRate.Floating)?.let { f ->
            requireNotNull(curves.curves[f.index]) {
                "curve set ${curves.id} has no ${f.index.name} curve, which floating loan ${instrument.id} needs"
            }
        }
        val loan = AmortisingLoan(
            currency = instrument.currency,
            outstandingPrincipal = instrument.outstanding,
            rate = rate,
            periodsPerYear = ext.periodsPerYear,
            remainingPeriods = ext.remainingPeriods,
            method = ext.method,
            nextDueDate = requireNotNull(ext.nextDueDate) { "loan ${instrument.id} has no remaining installment" },
            contractualSchedule = ext.remainingInstallments,
        )
        return AmortisingLoanCashFlows.expand(loan, curve)
    }
}
