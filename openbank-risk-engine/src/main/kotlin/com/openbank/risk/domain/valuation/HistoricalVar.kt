// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.finance.pricing.PRICING_MC
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.ZeroRateAdjustment
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** One day of market history: the curve set recorded for [date] and the FX rates in effect on it. */
data class HistoricalMarket(
    val date: LocalDate,
    val curves: Map<CurveIndex, Curve>,
    val fxToReporting: Map<String, BigDecimal>,
)

/** One scenario: the move from [from] to [to], applied to today's market. */
data class VarScenario(val from: LocalDate, val to: LocalDate, val pnl: BigDecimal)

/** VaR and ES as positive LOSSES, or the data gap that prevents stating them. */
data class VarFigures(val valueAtRisk: BigDecimal, val expectedShortfall: BigDecimal)

/**
 * Historical-simulation VaR by FULL REVALUATION (ADR-0344 D8).
 *
 * **Scenarios.** For every pair of history days `h` apart (`horizonDays`, overlapping windows), the
 * move is: per curve index, the ABSOLUTE change of the zero rate at each time-to-maturity
 * (`z_to(to + τ) − z_from(from + τ)`), added to today's curve at the same τ; per currency, the
 * RELATIVE change of its reporting-currency rate. Every deal is repriced on the moved market and the
 * scenario P&L is the change in book PV. A pair missing a curve or a rate the book needs is not a
 * scenario (it is counted as skipped), never filled in.
 *
 * **Quantile.** Losses `L = −P&L` sorted ascending `L(1) ≤ … ≤ L(n)`; VaR at confidence α is the
 * order statistic `L(⌈α·n⌉)` — no interpolation, the conservative empirical quantile — and ES is the
 * mean of `L(⌈α·n⌉) … L(n)`, so ES ≥ VaR by construction.
 */
object HistoricalVar {
    private val EPOCH: LocalDate = LocalDate.EPOCH

    /** Inverts the curve-time axis (`Curve.yearFraction`, the kernel ACT/365F) back to whole days. */
    private val ACT_365F_DAYS = BigDecimal.valueOf(
        DayCount.ACT_365_FIXED.yearFraction(EPOCH, EPOCH.plusYears(1)).denominator,
    )

    fun figures(pnl: List<BigDecimal>, confidence: BigDecimal): VarFigures {
        require(pnl.isNotEmpty()) { "VaR needs at least one scenario" }
        require(confidence > BigDecimal.ZERO && confidence < BigDecimal.ONE) {
            "confidence must be in (0, 1): $confidence"
        }
        val losses = pnl.map { it.negate() }.sorted()
        val k = confidence.multiply(
            BigDecimal(losses.size),
        ).setScale(0, RoundingMode.CEILING).toInt().coerceIn(1, losses.size)
        val tail = losses.subList(k - 1, losses.size)
        val es = tail.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(tail.size), PRICING_MC)
        return VarFigures(losses[k - 1], es)
    }

    /**
     * Today's [base] moved by the change between [from] and [to], or null when an input used by the
     * prepared book is absent. Unused curves and FX rates remain at base rather than discarding a
     * valid scenario for an unrelated market gap.
     */
    fun shocked(
        base: MarketState,
        from: HistoricalMarket,
        to: HistoricalMarket,
        requiredCurves: Set<CurveIndex> = base.curves.keys,
        requiredFx: Set<String> = base.fxToReporting.keys,
    ): MarketState? {
        val curves = base.curves.mapValues { (index, curve) ->
            if (index !in requiredCurves) return@mapValues curve
            val a = from.curves[index] ?: return null
            val b = to.curves[index] ?: return null
            curve.shifted(
                ZeroRateAdjustment { t, z ->
                    val days = t.multiply(ACT_365F_DAYS).setScale(0, RoundingMode.HALF_EVEN).toLong()
                    z.add(b.zeroRate(b.asOf.plusDays(days)).subtract(a.zeroRate(a.asOf.plusDays(days))))
                },
            )
        }
        val fx = base.fxToReporting.mapValues { (ccy, rate) ->
            if (ccy !in requiredFx) return@mapValues rate
            val r0 = from.fxToReporting[ccy] ?: return null
            val r1 = to.fxToReporting[ccy] ?: return null
            rate.multiply(r1, PRICING_MC).divide(r0, PRICING_MC)
        }
        return base.copy(curves = curves, fxToReporting = fx)
    }

    /** The overlapping `horizonDays`-apart pairs of [history] (sorted by date). */
    fun pairs(history: List<HistoricalMarket>, horizonDays: Int): List<Pair<HistoricalMarket, HistoricalMarket>> {
        require(horizonDays >= 1) { "horizonDays must be at least 1" }
        val sorted = history.sortedBy { it.date }
        return (horizonDays until sorted.size).map { sorted[it - horizonDays] to sorted[it] }
    }
}
