// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A tenor-dependent change applied on top of a curve's interpolated zero rate (IRRBB non-parallel
 * scenarios, ADR-0313 phase 1). [adjust] receives the ACT/365F year fraction from as-of and the
 * BASE zero rate at that tenor and returns the SHIFTED zero rate — the base is passed so a
 * post-shock floor that must not push a rate already below it further down can be expressed.
 */
fun interface ZeroRateAdjustment {
    fun adjust(yearFraction: BigDecimal, baseRate: BigDecimal): BigDecimal
}

/** One node of a [Curve]: the continuously-compounded zero rate to [date], as a fraction. */
data class CurvePillar(val date: LocalDate, val zeroRate: BigDecimal)

/**
 * A zero curve for one [CurveIndex] as of [asOf] (ADR-0313 D4).
 *
 * **Conventions, stated once.** Rates are continuously compounded zero rates; the day count is
 * ACT/365F, so the year fraction to a date is `days(asOf, date) / 365`; `DF(t) = exp(−r(t)·t)`.
 *
 * **Interpolation is linear on zero rates in time between pillars, flat beyond both ends.** Chosen
 * because it is the simplest scheme that is exact at every pillar and monotone between them, so a
 * reviewer can check any interpolated value by hand. Its known weakness — forward rates jump at
 * pillars — does not matter for phase-0 gap and PV figures; a smoother scheme (monotone convex)
 * would be a versioned change of this class, not a silent one.
 *
 * Validation is strict: at least one pillar, every pillar strictly after [asOf] (so `DF(asOf) = 1`
 * holds by construction rather than by a stored point), dates strictly increasing.
 */
class Curve(
    val index: CurveIndex,
    val asOf: LocalDate,
    pillars: List<CurvePillar>,
    /**
     * Applied to the interpolated zero rate at every date, so a shape that is not linear between
     * pillars (the exponential short/long shocks) is exact at each flow's own tenor rather than
     * sampled at the pillars and re-interpolated. Null for an unshocked curve.
     */
    val adjustment: ZeroRateAdjustment? = null,
) {

    val pillars: List<CurvePillar> = pillars.toList()

    init {
        require(this.pillars.isNotEmpty()) { "curve ${index.name} needs at least one pillar" }
        require(this.pillars.first().date.isAfter(asOf)) {
            "curve ${index.name}: every pillar must be after asOf $asOf, first is ${this.pillars.first().date}"
        }
        this.pillars.zipWithNext().forEach { (a, b) ->
            require(b.date.isAfter(a.date)) {
                "curve ${index.name}: pillars must be strictly increasing, ${b.date} follows ${a.date}"
            }
        }
    }

    /** ACT/365F year fraction from [asOf]. */
    fun yearFraction(date: LocalDate): BigDecimal = yearFraction(asOf, date)

    fun zeroRate(date: LocalDate): BigDecimal {
        val base = baseZeroRate(date)
        return adjustment?.adjust(yearFraction(maxOf(date, asOf)), base) ?: base
    }

    private fun baseZeroRate(date: LocalDate): BigDecimal {
        val first = pillars.first()
        val last = pillars.last()
        if (!date.isAfter(first.date)) return first.zeroRate
        if (!date.isBefore(last.date)) return last.zeroRate
        val upperIdx = pillars.indexOfFirst { !it.date.isBefore(date) }
        val upper = pillars[upperIdx]
        if (upper.date == date) return upper.zeroRate
        val lower = pillars[upperIdx - 1]
        val span = BigDecimal(ChronoUnit.DAYS.between(lower.date, upper.date))
        val offset = BigDecimal(ChronoUnit.DAYS.between(lower.date, date))
        val weight = offset.divide(span, BigMath.MC)
        return lower.zeroRate.add(upper.zeroRate.subtract(lower.zeroRate).multiply(weight, BigMath.MC), BigMath.MC)
    }

    /** `exp(−r·t)`; exactly 1 at [asOf]. A date before [asOf] is a flow already due: also 1. */
    fun discountFactor(date: LocalDate): BigDecimal {
        if (!date.isAfter(asOf)) return BigDecimal.ONE
        return BigMath.exp(zeroRate(date).multiply(yearFraction(date), BigMath.MC).negate())
    }

    /**
     * Simple (money-market) forward rate over `[start, end]`, ACT/365F:
     * `(DF(start)/DF(end) − 1) / τ`. The rate a simple-interest index fixing over that period
     * would be, if the curve is right.
     */
    fun forwardRate(start: LocalDate, end: LocalDate): BigDecimal {
        require(end.isAfter(start)) { "forward period must end after it starts: $start..$end" }
        val ratio = discountFactor(start).divide(discountFactor(end), BigMath.MC)
        return ratio.subtract(BigDecimal.ONE).divide(yearFraction(start, end), BigMath.MC)
    }

    /**
     * Continuously-compounded forward over `[start, end]`: `(ln DF(start) − ln DF(end)) / τ`,
     * i.e. the average of the curve over the period. Unlike [forwardRate] it does not depend on
     * the period's length when the curve is flat, which is why the floating-leg projection uses it
     * (see `AmortisingLoanCashFlows`). Rounded to [RATE_SCALE] places: that removes exp/ln
     * round-trip noise at 1e-30 while staying far below any published fixing's precision.
     */
    fun averageForwardRate(start: LocalDate, end: LocalDate): BigDecimal {
        require(end.isAfter(start)) { "forward period must end after it starts: $start..$end" }
        val lnStart = BigMath.ln(discountFactor(start))
        val lnEnd = BigMath.ln(discountFactor(end))
        return lnStart.subtract(lnEnd).divide(yearFraction(start, end), BigMath.MC)
            .setScale(RATE_SCALE, RoundingMode.HALF_EVEN)
    }

    /**
     * A new curve with [shift] applied at every tenor on top of this curve's own rates. Composes
     * with an adjustment this curve already carries.
     */
    fun shifted(shift: ZeroRateAdjustment): Curve {
        val inner = adjustment
        val composed = if (inner == null) {
            shift
        } else {
            ZeroRateAdjustment { t, base -> shift.adjust(t, inner.adjust(t, base)) }
        }
        return Curve(index, asOf, pillars, composed)
    }

    /** A new curve with every zero rate moved by [basisPoints] (IRRBB parallel scenarios). */
    fun parallelShift(basisPoints: BigDecimal): Curve {
        val shift = basisPoints.movePointLeft(BP_DECIMALS)
        return Curve(index, asOf, pillars.map { it.copy(zeroRate = it.zeroRate.add(shift)) }, adjustment)
    }

    companion object {
        const val DAYS_PER_YEAR = 365
        const val RATE_SCALE = 10
        private const val BP_DECIMALS = 4

        fun yearFraction(from: LocalDate, to: LocalDate): BigDecimal =
            BigDecimal(ChronoUnit.DAYS.between(from, to)).divide(BigDecimal(DAYS_PER_YEAR), BigMath.MC)
    }
}
