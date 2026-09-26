// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.CashFlowKind
import com.openbank.risk.domain.cashflow.LoanRate
import com.openbank.risk.domain.cashflow.NonMaturityDepositCashFlows
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.cashflow.TimeBucket
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.time.LocalDate

/** Where a repricing amount comes from — shown so a gap figure can be traced to its rule. */
enum class RepricingSource { FIXED_LOAN_PRINCIPAL, FLOATING_LOAN_RESET, NMD_BEHAVIOURAL }

/**
 * One notional that reprices on [date]. [amount] is signed from the bank's side like a
 * [com.openbank.risk.domain.cashflow.CashFlow]: an asset positive, a liability negative.
 */
data class RepricingAmount(
    val date: LocalDate,
    val currency: String,
    val amount: BigDecimal,
    val source: RepricingSource,
)

data class GapBucket(
    val bucket: TimeBucket,
    val assets: BigDecimal,
    val liabilities: BigDecimal,
    val gap: BigDecimal,
    val cumulativeGap: BigDecimal,
)

/** A currency's repricing gap. [liabilities] are magnitudes (positive); `gap = assets − liabilities`. */
data class CurrencyGap(
    val currency: String,
    val buckets: List<GapBucket>,
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    val totalGap: BigDecimal,
)

/**
 * Repricing notionals of a snapshot, bucketed by REPRICING date rather than maturity
 * (ADR-0313 phase 1). The rules, one per source:
 *
 *  - a FIXED loan reprices only when its principal comes back: each contractual principal
 *    installment on its due date;
 *  - a FLOATING loan reprices in full at its next reset — the whole outstanding on
 *    `nextResetDate`, or on the next due date when the loan resets every payment period (no reset
 *    terms) or when the next reset is not after as-of;
 *  - a non-maturity deposit reprices as the behavioural model runs it off. The model
 *    ([BehaviouralModel], `nmd-linear-core`) carries NO repricing assumption separate from its
 *    run-off, so repricing = run-off: the volatile part overnight, the core in monthly slices.
 *    This is an assumption, reported with every result, not a finding.
 *
 * Only principal is a repricing notional; interest flows are not (they are in ΔEVE through the
 * full cash flows). So a currency's total gap equals the sum of its loans' outstanding minus its
 * deposits, to the cent.
 */
object RepricingGap {

    fun amounts(
        positions: List<Position>,
        instruments: List<Instrument>,
        asOf: LocalDate,
        model: BehaviouralModel,
    ): List<RepricingAmount> {
        val deposits = positions.filter { it.kind == PositionKind.SUB_LEDGER }.flatMap { p ->
            NonMaturityDepositCashFlows.expand(p.amount.negate(), p.currency, asOf, model)
                .filter { it.kind == CashFlowKind.PRINCIPAL }
                .map { RepricingAmount(it.date, it.currency, it.amount, RepricingSource.NMD_BEHAVIOURAL) }
        }
        val loans = instruments
            .filter { it.kind in SnapshotCashFlowProjection.LOAN_KINDS && it.outstanding.signum() != 0 }
            .flatMap { loan(it, asOf) }
        return deposits + loans
    }

    private fun loan(instrument: Instrument, asOf: LocalDate): List<RepricingAmount> {
        val ext = requireNotNull(instrument.extension as? LoanExtension) { "loan ${instrument.id} has no loan terms" }
        return when (val rate = SnapshotCashFlowProjection.loanRate(instrument)) {
            is LoanRate.Fixed -> ext.remainingInstallments.map {
                RepricingAmount(it.dueDate, instrument.currency, it.principal, RepricingSource.FIXED_LOAN_PRINCIPAL)
            }
            is LoanRate.Floating -> {
                val nextDue = requireNotNull(ext.nextDueDate) { "loan ${instrument.id} has no remaining installment" }
                val reset = rate.nextResetDate?.takeIf { rate.resetFrequencyMonths != null && it.isAfter(asOf) }
                listOf(
                    RepricingAmount(
                        date = reset ?: nextDue,
                        currency = instrument.currency,
                        amount = instrument.outstanding,
                        source = RepricingSource.FLOATING_LOAN_RESET,
                    ),
                )
            }
        }
    }

    fun gap(currency: String, amounts: List<RepricingAmount>, asOf: LocalDate): CurrencyGap {
        val mine = amounts.filter { it.currency == currency }
        var cumulative = BigDecimal.ZERO
        val buckets = TimeBucket.entries.map { b ->
            val inBucket = mine.filter { TimeBucket.of(it.date, asOf) == b }
            val assets = inBucket.filter { it.amount.signum() > 0 }.fold(BigDecimal.ZERO) { a, r -> a.add(r.amount) }
            val liabilities = inBucket.filter { it.amount.signum() < 0 }
                .fold(BigDecimal.ZERO) { a, r -> a.add(r.amount.negate()) }
            val gap = assets.subtract(liabilities)
            cumulative = cumulative.add(gap)
            GapBucket(b, assets, liabilities, gap, cumulative)
        }
        val assets = buckets.fold(BigDecimal.ZERO) { a, b -> a.add(b.assets) }
        val liabilities = buckets.fold(BigDecimal.ZERO) { a, b -> a.add(b.liabilities) }
        return CurrencyGap(currency, buckets, assets, liabilities, assets.subtract(liabilities))
    }
}
