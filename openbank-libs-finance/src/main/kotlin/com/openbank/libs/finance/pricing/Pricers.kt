// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.pricing

import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.libs.finance.cashflow.ProjectedCashFlow
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.schedule.Schedule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period

/** A valuation as a function of the discount curve — the shape every sensitivity bumps. */
fun interface Valuation {
    fun presentValue(curve: DiscountCurve): BigDecimal
}

/** Present value of projected flows. */
object LegPricer {
    /**
     * `Σ amount · DF(paymentDate)` over flows paid strictly AFTER [asOf] (a flow paid on [asOf] is
     * treated as settled), unrounded at [PRICING_MC]. Flows must all be in one currency — the
     * curve's; convert before summing across currencies.
     */
    fun presentValue(
        flows: List<ProjectedCashFlow>,
        curve: DiscountCurve,
        asOf: LocalDate = curve.valuationDate,
    ): BigDecimal {
        require(flows.map { it.currency }.distinct().size <= 1) { "flows span several currencies" }
        return flows.filter { it.paymentDate.isAfter(asOf) }
            .fold(BigDecimal.ZERO) { pv, f ->
                pv.add(f.amount.multiply(curve.discountFactor(f.paymentDate), PRICING_MC), PRICING_MC)
            }
    }

    /**
     * Single-curve valuation of a generated schedule: floating periods without an observed fixing
     * are projected from the forwards implied by the SAME curve that discounts, so a curve bump
     * moves both projection and discounting consistently.
     */
    fun valuation(schedule: Schedule, fixings: FixingSource = FixingSource.NONE): Valuation = Valuation { curve ->
        presentValue(CashFlowProjector.project(schedule, fixings, curve.impliedForwards()).requireComplete(), curve)
    }

    /** Valuation of several legs (an instrument) as the sum of its legs. */
    fun valuation(legs: List<Valuation>): Valuation = Valuation { curve ->
        legs.fold(BigDecimal.ZERO) { pv, leg -> pv.add(leg.presentValue(curve), PRICING_MC) }
    }
}

/** One key-rate bucket's sensitivity. */
data class BucketDv01(val tenor: Period, val dv01: BigDecimal)

/**
 * Curve sensitivities by bump-and-revalue.
 *
 * Sign convention: PV01 = PV(curve + 1bp) − PV(curve). A receiver of fixed (long duration) has a
 * NEGATIVE PV01; a payer of fixed a positive one.
 */
object Sensitivities {
    val ONE_BP: BigDecimal = BigDecimal("0.0001")

    fun pv01(valuation: Valuation, curve: DiscountCurve): BigDecimal =
        valuation.presentValue(ShiftedCurve(curve, ONE_BP)).subtract(valuation.presentValue(curve), PRICING_MC)

    /**
     * Key-rate DV01 per tenor bucket. Bucket `i` bumps the zero curve by 1bp with a triangular
     * weight that is 1 at pillar `i` and falls linearly to 0 at its neighbours; the first bucket is
     * flat at 1 before its pillar and the last flat at 1 after its pillar. The weights sum to 1 at
     * every time, so the buckets partition the parallel shift and their sum approximates [pv01]
     * to second order in the bump.
     */
    fun bucketedDv01(valuation: Valuation, curve: DiscountCurve, tenors: List<Period>): List<BucketDv01> {
        require(tenors.isNotEmpty()) { "bucketed DV01 needs at least one tenor" }
        val pillars = tenors.map { timeTo(curve.valuationDate, curve.valuationDate.plus(it)) }
        require(pillars.zipWithNext().all { (a, b) -> b > a }) { "tenors must be strictly increasing: $tenors" }
        val base = valuation.presentValue(curve)
        return tenors.indices.map { i ->
            val bumped = ShiftedCurve(curve, ONE_BP) { t -> hat(pillars, i, t) }
            BucketDv01(tenors[i], valuation.presentValue(bumped).subtract(base, PRICING_MC))
        }
    }

    internal fun hat(pillars: List<BigDecimal>, i: Int, t: BigDecimal): BigDecimal {
        val p = pillars[i]
        return when {
            t <= p && i == 0 -> BigDecimal.ONE
            t >= p && i == pillars.lastIndex -> BigDecimal.ONE
            t <= p -> ramp(t, pillars[i - 1], p)
            else -> BigDecimal.ONE.subtract(ramp(t, p, pillars[i + 1]))
        }
    }

    /** 0 at or before [from], 1 at or after [to], linear in between. */
    private fun ramp(t: BigDecimal, from: BigDecimal, to: BigDecimal): BigDecimal = when {
        t <= from -> BigDecimal.ZERO
        t >= to -> BigDecimal.ONE
        else -> t.subtract(from).divide(to.subtract(from), PRICING_MC)
    }
}

private const val PIP_DECIMALS = 4

/** An FX outright forward quote. */
data class FxForwardQuote(val forwardRate: BigDecimal, val forwardPoints: BigDecimal)

/**
 * FX forward and swap arithmetic by covered interest parity. A rate is quoted as units of QUOTE
 * currency per one unit of BASE currency (e.g. EUR/CZK = CZK per EUR).
 */
object FxForwardPricer {
    /** Pip factor of most currency pairs (points = 10 000 × rate difference). */
    val DEFAULT_POINT_FACTOR: BigDecimal = BigDecimal.ONE.movePointRight(PIP_DECIMALS)

    /**
     * `F = S · (DF_base(T)/DF_base(spot)) / (DF_quote(T)/DF_quote(spot))`, with points
     * `(F − S) · pointFactor`. Both curves are discount curves in their own currency.
     */
    fun forward(
        spot: BigDecimal,
        spotDate: LocalDate,
        maturity: LocalDate,
        base: DiscountCurve,
        quote: DiscountCurve,
        pointFactor: BigDecimal = DEFAULT_POINT_FACTOR,
    ): FxForwardQuote {
        val baseGrowth = base.discountFactor(maturity).divide(base.discountFactor(spotDate), PRICING_MC)
        val quoteGrowth = quote.discountFactor(maturity).divide(quote.discountFactor(spotDate), PRICING_MC)
        val forward = spot.multiply(baseGrowth, PRICING_MC).divide(quoteGrowth, PRICING_MC)
        return FxForwardQuote(forward, forward.subtract(spot).multiply(pointFactor, PRICING_MC))
    }

    /**
     * Present value, in QUOTE currency, of buying [baseNotional] of base at [contractRate] for
     * [maturity]: `N · (F − K) · DF_quote(T)`, F from [forward]. Negate for a sale.
     */
    fun presentValue(
        baseNotional: BigDecimal,
        contractRate: BigDecimal,
        forward: FxForwardQuote,
        maturity: LocalDate,
        quote: DiscountCurve,
    ): BigDecimal = baseNotional.multiply(forward.forwardRate.subtract(contractRate), PRICING_MC)
        .multiply(quote.discountFactor(maturity), PRICING_MC)
}

/**
 * FRA settlement (ISDA FRA discounting): the amount paid at the START of the contract period,
 * `N · (L − K) · τ / (1 + L · τ)`. Positive means the BUYER (payer of the fixed rate [contractRate])
 * receives; the result is signed for [side] — a buyer is [PayReceive.PAY] of the fixed rate.
 */
object FraPricer {
    fun settlementAmount(
        notional: BigDecimal,
        contractRate: BigDecimal,
        fixing: BigDecimal,
        yearFraction: YearFraction,
        side: PayReceive,
    ): BigDecimal {
        val tau = yearFraction.toBigDecimal(PRICING_MC)
        val undiscounted = notional.multiply(fixing.subtract(contractRate), PRICING_MC).multiply(tau, PRICING_MC)
        val buyer = undiscounted.divide(BigDecimal.ONE.add(fixing.multiply(tau, PRICING_MC)), PRICING_MC)
        return if (side == PayReceive.PAY) buyer else buyer.negate()
    }
}
