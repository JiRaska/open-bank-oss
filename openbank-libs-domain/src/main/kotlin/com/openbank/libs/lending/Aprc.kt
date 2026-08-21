// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Annual percentage rate of charge — RPSN in Czech, APRC/APR elsewhere (Consumer Credit Directive
 * 2023/2225 Annex III; the same equation as the earlier 2008/48/EC Annex I).
 *
 * The APRC is the rate X that equates, on an annual compounding basis, the present value of what the
 * customer receives with the present value of everything they pay:
 *
 *     Σ  Dₖ / (1 + X)^tₖ   =   Σ  Pⱼ / (1 + X)^sⱼ
 *
 * where D are drawdowns, P are payments (instalments AND charges), and t/s are the intervals in
 * YEARS from the first drawdown. There is no closed form; the directive itself expects a numerical
 * solution, so this solves by bisection.
 *
 * ## Why this is its own file, and why nothing else may compute it
 *
 * The APRC is the number a customer compares lenders on and the one a regulator checks. Under
 * ADR-0269 rule 4 it may only ever come from the server, and within the server it may only come
 * from here: two implementations that disagree in the fourth decimal is a mis-disclosure, not a
 * rounding difference.
 *
 * ## Why fees are an input, not an afterthought
 *
 * The whole point of the APRC is that it prices the total cost of credit, not the interest. A quote
 * that reports the nominal rate as "the APRC" whenever there are no fees works by accident and
 * lies the moment a fee is added, so charges enter the cashflow list explicitly.
 */
object Aprc {

    private val MC = MathContext.DECIMAL128
    private val ONE = BigDecimal.ONE

    /** Bisection bounds: 0% to 10 000% a year. Above that the answer is "this is not a loan". */
    private val LOWER = BigDecimal.ZERO
    private val UPPER = BigDecimal("100")

    /** Absolute tolerance on the discounted-balance equation, in currency units. */
    private val TOLERANCE = BigDecimal("0.0000001")

    private const val MAX_ITERATIONS = 200

    /** Scale of the returned rate as a FRACTION (0.0891 = 8.91%): six places, i.e. four in percent. */
    private const val RATE_SCALE = 6

    /**
     * One cash movement in the APRC equation.
     *
     * [yearsFromStart] is the interval from the first drawdown expressed in years — the directive's
     * own unit — so a monthly instalment k falls at k/12. Fractions are deliberate: rounding periods
     * to whole years would move the answer by more than the disclosure tolerance.
     */
    data class CashFlow(val yearsFromStart: BigDecimal, val amount: BigDecimal)

    /**
     * Solve for the APRC as a fraction, e.g. `0.089100` for 8.91%.
     *
     * [advances] are what the customer receives (drawdowns), [payments] everything they pay back,
     * including charges. Both must be positive amounts; the sign convention lives here rather than
     * in the caller, because a caller that passes a negative payment silently inverts the equation.
     *
     * Returns null when the equation has no solution in the searched range — a loan repaying less
     * than it advanced, or figures so distorted that no positive rate balances them. Null is
     * deliberate: an APRC that cannot be computed must not be rendered as 0%, which reads to a
     * customer as free credit.
     */
    fun solve(advances: List<CashFlow>, payments: List<CashFlow>): BigDecimal? {
        require(advances.isNotEmpty()) { "at least one advance is required" }
        require(payments.isNotEmpty()) { "at least one payment is required" }
        require(advances.all { it.amount > BigDecimal.ZERO }) { "advances must be positive" }
        require(payments.all { it.amount > BigDecimal.ZERO }) { "payments must be positive" }
        require((advances + payments).all { it.yearsFromStart >= BigDecimal.ZERO }) {
            "cash flows must not precede the first drawdown"
        }

        // f(X) = PV(payments at X) − PV(advances at X). Strictly decreasing in X, so bisection is
        // safe: more discounting shrinks the (later) payments faster than the (earlier) advances.
        fun f(rate: BigDecimal): BigDecimal =
            presentValue(payments, rate).subtract(presentValue(advances, rate), MC)

        val atZero = f(LOWER)
        // Total repayments no greater than the advance: no positive rate can balance the equation.
        if (atZero <= BigDecimal.ZERO) return null
        if (f(UPPER) > BigDecimal.ZERO) return null

        var low = LOWER
        var high = UPPER
        repeat(MAX_ITERATIONS) {
            val mid = low.add(high, MC).divide(BigDecimal(2), MC)
            val value = f(mid)
            if (value.abs() < TOLERANCE) return mid.setScale(RATE_SCALE, RoundingMode.HALF_UP)
            if (value > BigDecimal.ZERO) low = mid else high = mid
        }
        return low.add(high, MC).divide(BigDecimal(2), MC).setScale(RATE_SCALE, RoundingMode.HALF_UP)
    }

    private fun presentValue(flows: List<CashFlow>, rate: BigDecimal): BigDecimal =
        flows.fold(BigDecimal.ZERO) { acc, flow ->
            acc.add(flow.amount.divide(discountFactor(rate, flow.yearsFromStart), MC), MC)
        }

    /**
     * (1 + rate)^years for a fractional exponent, via `Math.pow` on doubles.
     *
     * Double precision is ~15 significant digits; the APRC is disclosed to one decimal place in
     * percent, so the error here is some fourteen orders of magnitude below anything that could
     * change a published figure. BigDecimal has no fractional power, and a hand-rolled series
     * expansion would trade a provably irrelevant error for an unprovable one.
     */
    private fun discountFactor(rate: BigDecimal, years: BigDecimal): BigDecimal {
        if (years.signum() == 0) return ONE
        val factor = Math.pow(ONE.add(rate).toDouble(), years.toDouble())
        return BigDecimal(factor, MC)
    }
}
