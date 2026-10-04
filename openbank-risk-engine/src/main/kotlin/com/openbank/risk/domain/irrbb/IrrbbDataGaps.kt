// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.cashflow.CashFlowAggregation
import com.openbank.risk.domain.cashflow.LoanRate
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * What an IRRBB figure does NOT capture, stated with the figure instead of discovered later. Each
 * code is a modelling simplification or a missing input, never an error: the numbers are still
 * computed, and these say how far to trust them.
 */
enum class IrrbbGapCode {
    /**
     * A flow falls after the last pillar of a curve it is priced on, so the base zero rate there
     * is the last pillar's rate held flat (see [com.openbank.risk.domain.curve.Curve]). Operator
     * curve sets carry money-market tenors only (ON..1Y), so every flow beyond one year is
     * discounted — and every floating fixing beyond it projected — on an extrapolated rate. The
     * supervisory shocks themselves are still applied at each flow's own tenor.
     */
    CURVE_EXTRAPOLATED_FLAT,

    /** Loans run off on their contractual schedule: no prepayment (CPR) model is applied. */
    PREPAYMENT_NOT_MODELLED,

    /**
     * Non-maturity deposits use the phase-0 linear core/volatile run-off with no deposit beta and
     * repricing equal to run-off; no behavioural NMD model calibrated to the bank's own history.
     */
    NMD_BEHAVIOUR_SIMPLIFIED,

    /** Discounted flows carry the full contractual rate; commercial margins are not stripped out. */
    COMMERCIAL_MARGIN_INCLUDED,

    /** Instruments of the run that the IRRBB projection does not read (treasury money-market deals). */
    INSTRUMENTS_NOT_PROJECTED,
}

/**
 * One gap. [currency], [curveIndex] and the figures are set where the code is per curve
 * ([IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT]); [detail] is the English statement of it.
 */
data class IrrbbDataGap(
    val code: IrrbbGapCode,
    val currency: String? = null,
    val curveIndex: CurveIndex? = null,
    val lastPillarDate: LocalDate? = null,
    val lastFlowDate: LocalDate? = null,
    val flowsBeyond: Int? = null,
    /** Base-scenario PV of the flows beyond [lastPillarDate]; discounting curve only. */
    val basePvBeyond: BigDecimal? = null,
    val count: Int? = null,
    val detail: String,
)

object IrrbbDataGaps {

    /**
     * Every gap of an IRRBB result over [positions] and [instruments] priced on [curves].
     * [baseFlows] are the base-scenario flows per currency for the [evaluated] currencies.
     */
    fun of(
        positions: List<Position>,
        instruments: List<Instrument>,
        curves: CurveSet,
        baseFlows: Map<String, List<CashFlow>>,
        evaluated: List<String>,
    ): List<IrrbbDataGap> {
        val loans = instruments.filter {
            it.kind in SnapshotCashFlowProjection.LOAN_KINDS &&
                it.outstanding.signum() != 0
        }
        val deposits = positions.filter { it.kind == PositionKind.SUB_LEDGER }
        val deals = instruments.filter { it.kind == InstrumentKind.MONEY_MARKET_DEAL }
        val extrapolated = evaluated.flatMap { extrapolation(it, baseFlows[it].orEmpty(), loans, curves) }
        val behavioural = buildList {
            if (loans.isNotEmpty()) {
                add(
                    IrrbbDataGap(
                        code = IrrbbGapCode.PREPAYMENT_NOT_MODELLED,
                        count = loans.size,
                        detail = "${loans.size} loan(s) run off on their contractual schedule; no prepayment " +
                            "rate is applied, so EVE is overstated for loans that would prepay when rates fall.",
                    ),
                )
                add(
                    IrrbbDataGap(
                        code = IrrbbGapCode.COMMERCIAL_MARGIN_INCLUDED,
                        detail = "Loan flows are discounted with their full contractual rate, commercial margin " +
                            "included; no margin is stripped out of the EVE flows.",
                    ),
                )
            }
            if (deposits.isNotEmpty()) {
                add(
                    IrrbbDataGap(
                        code = IrrbbGapCode.NMD_BEHAVIOUR_SIMPLIFIED,
                        count = deposits.size,
                        detail = "Non-maturity deposits use the phase-0 linear core/volatile run-off: no deposit " +
                            "beta (full pass-through in NII), repricing equal to run-off, not calibrated to the " +
                            "bank's own deposit history.",
                    ),
                )
            }
            if (deals.isNotEmpty()) {
                add(
                    IrrbbDataGap(
                        code = IrrbbGapCode.INSTRUMENTS_NOT_PROJECTED,
                        count = deals.size,
                        detail = "${deals.size} treasury money-market deal(s) of the run are not in the repricing " +
                            "gap, EVE or NII.",
                    ),
                )
            }
        }
        return extrapolated + behavioural
    }

    /**
     * Per curve of [currency] the projection actually uses — its discounting curve, and the index
     * curve of each floating loan — the flows that fall after that curve's last pillar.
     */
    fun extrapolation(
        currency: String,
        flows: List<CashFlow>,
        loans: List<Instrument>,
        curves: CurveSet,
    ): List<IrrbbDataGap> {
        val lastFlow = flows.maxOfOrNull { it.date } ?: return emptyList()
        val discount = curves.discountCurveFor(currency) ?: return emptyList()
        val discountEnd = discount.pillars.last().date
        val gaps = mutableListOf<IrrbbDataGap>()
        val beyond = flows.filter { it.date.isAfter(discountEnd) }
        if (beyond.isNotEmpty()) {
            val pv = CashFlowAggregation.presentValue(beyond, discount, CurrencyCode.of(currency).defaultFractionDigits)
            gaps += IrrbbDataGap(
                code = IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT,
                currency = currency,
                curveIndex = discount.index,
                lastPillarDate = discountEnd,
                lastFlowDate = lastFlow,
                flowsBeyond = beyond.size,
                basePvBeyond = pv,
                detail = "${discount.index.name} ends on $discountEnd; ${beyond.size} $currency flow(s) up to " +
                    "$lastFlow are discounted at its last zero rate held flat (base PV of those flows $pv " +
                    "$currency). The supervisory shocks are still applied at each flow's own tenor.",
            )
        }
        val floatingIndices = loans
            .filter { it.currency == currency }
            .mapNotNull { (SnapshotCashFlowProjection.loanRate(it) as? LoanRate.Floating)?.index }
            .filter { it != discount.index }
            .toSortedSet()
        floatingIndices.forEach { index ->
            val curve = curves.curves[index] ?: return@forEach
            val end = curve.pillars.last().date
            if (lastFlow.isAfter(end)) {
                gaps += IrrbbDataGap(
                    code = IrrbbGapCode.CURVE_EXTRAPOLATED_FLAT,
                    currency = currency,
                    curveIndex = index,
                    lastPillarDate = end,
                    lastFlowDate = lastFlow,
                    detail = "${index.name} ends on $end; floating fixings after it are projected from its last " +
                        "zero rate held flat.",
                )
            }
        }
        return gaps
    }
}
