// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.pricing

import com.openbank.libs.domain.calendar.DayCount
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/** Working precision of all pricing arithmetic (34 significant digits). */
val PRICING_MC: MathContext = MathContext.DECIMAL128

/**
 * A discount curve: the price today of one unit paid on a date. Implemented by the risk engine
 * over its own curve sets; this module only consumes it. [discountFactor] must be 1 at the
 * [valuationDate] and positive everywhere.
 */
interface DiscountCurve {
    val valuationDate: LocalDate

    fun discountFactor(date: LocalDate): BigDecimal

    /** Simply-compounded forwards implied by this curve: `(DF(s) / DF(e) − 1) / τ(s, e)`. */
    fun impliedForwards(): ForwardCurve = ForwardCurve { start, end, dayCount ->
        val tau = dayCount.yearFraction(start, end).toBigDecimal(PRICING_MC)
        require(tau.signum() > 0) { "forward period [$start, $end) has no length" }
        discountFactor(start).divide(discountFactor(end), PRICING_MC).subtract(BigDecimal.ONE).divide(tau, PRICING_MC)
    }

    /** Continuously compounded zero rate to [date], ACT/365F time from [valuationDate]. */
    fun zeroRate(date: LocalDate): BigDecimal {
        val t = timeTo(valuationDate, date)
        require(t.signum() != 0) { "zero rate is undefined at the valuation date" }
        return BigMath.ln(discountFactor(date)).negate().divide(t, PRICING_MC)
    }
}

/** A forward-rate projection curve: the simply-compounded rate for `[start, end)` under [dayCount]. */
fun interface ForwardCurve {
    fun forwardRate(start: LocalDate, end: LocalDate, dayCount: DayCount): BigDecimal
}

/** Time in years (ACT/365F) — the curve-time axis every shift in this package uses. */
internal fun timeTo(from: LocalDate, to: LocalDate): BigDecimal =
    DayCount.ACT_365_FIXED.yearFraction(from, to).toBigDecimal(PRICING_MC)

/** A flat continuously compounded zero curve `DF(t) = exp(−r·t)`, t in ACT/365F years. */
class FlatZeroCurve(override val valuationDate: LocalDate, val rate: BigDecimal) : DiscountCurve {
    override fun discountFactor(date: LocalDate): BigDecimal =
        BigMath.exp(rate.negate().multiply(timeTo(valuationDate, date), PRICING_MC))
}

/**
 * [base] with its continuously compounded zero rates moved by [shift] times the weight
 * [weight]`(t)`: `DF'(t) = DF(t) · exp(−shift · w(t) · t)`. A weight of 1 everywhere is a parallel
 * shift; a triangular weight is one key-rate bucket.
 */
class ShiftedCurve(
    private val base: DiscountCurve,
    private val shift: BigDecimal,
    private val weight: (BigDecimal) -> BigDecimal = { BigDecimal.ONE },
) : DiscountCurve {
    override val valuationDate: LocalDate get() = base.valuationDate

    override fun discountFactor(date: LocalDate): BigDecimal {
        val t = timeTo(valuationDate, date)
        val bump = shift.multiply(weight(t), PRICING_MC).multiply(t, PRICING_MC)
        return base.discountFactor(date).multiply(BigMath.exp(bump.negate()), PRICING_MC)
    }
}

/** exp and ln over BigDecimal at [PRICING_MC]; a Double appears only as the Newton seed of [ln], never in a result. */
object BigMath {
    private const val MAX_TERMS = 200
    private const val HALVINGS_LIMIT = 64
    private val TWO = BigDecimal(2)
    private val EPS = BigDecimal.ONE.movePointLeft(PRICING_MC.precision + 2)

    /** e^x by argument halving and a Taylor series. */
    fun exp(x: BigDecimal): BigDecimal {
        var reduced = x
        var halvings = 0
        while (reduced.abs() > BigDecimal.ONE && halvings < HALVINGS_LIMIT) {
            reduced = reduced.divide(TWO, PRICING_MC)
            halvings++
        }
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        var n = 1
        while (n < MAX_TERMS) {
            term = term.multiply(reduced, PRICING_MC).divide(BigDecimal(n), PRICING_MC)
            sum = sum.add(term, PRICING_MC)
            if (term.abs() < EPS) break
            n++
        }
        repeat(halvings) { sum = sum.multiply(sum, PRICING_MC) }
        return sum
    }

    /** ln(x) for x > 0 by Newton iteration on [exp]. */
    fun ln(x: BigDecimal): BigDecimal {
        require(x.signum() > 0) { "ln is undefined for $x" }
        var y = BigDecimal(Math.log(x.toDouble())) // seed only; Newton converges to full precision
        repeat(MAX_TERMS) {
            val e = exp(y)
            val next = y.subtract(e.subtract(x).divide(e, PRICING_MC), PRICING_MC)
            if (next.subtract(y).abs() < EPS) return next
            y = next
        }
        return y
    }
}
