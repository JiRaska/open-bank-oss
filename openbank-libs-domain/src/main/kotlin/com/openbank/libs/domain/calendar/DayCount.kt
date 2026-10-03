// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * An exact year fraction `numerator / denominator`, kept as a reduced rational so that no
 * convention (1/365, 1/366, 3/360 …) is rounded before the caller decides where to round money.
 * The sign lives in [numerator]; [denominator] is always positive.
 */
class YearFraction private constructor(val numerator: Long, val denominator: Long) {

    operator fun plus(other: YearFraction): YearFraction = of(
        Math.addExact(
            Math.multiplyExact(numerator, other.denominator),
            Math.multiplyExact(other.numerator, denominator),
        ),
        Math.multiplyExact(denominator, other.denominator),
    )

    operator fun unaryMinus(): YearFraction = YearFraction(-numerator, denominator)

    fun isZero(): Boolean = numerator == 0L

    /** Decimal value at [mc] precision (for display and comparison; do money through [applyTo]). */
    fun toBigDecimal(mc: MathContext = MathContext.DECIMAL128): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), mc)

    /** `value × numerator / denominator`, rounded ONCE to [scale] with [rounding]. */
    fun applyTo(value: BigDecimal, scale: Int, rounding: RoundingMode): BigDecimal =
        value.multiply(BigDecimal(numerator)).divide(BigDecimal(denominator), scale, rounding)

    override fun equals(other: Any?): Boolean =
        other is YearFraction && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO = YearFraction(0, 1)

        fun of(numerator: Long, denominator: Long): YearFraction {
            require(denominator != 0L) { "denominator must not be zero" }
            val sign = if (denominator < 0) -1 else 1
            val g = gcd(Math.abs(numerator), Math.abs(denominator)).coerceAtLeast(1)
            return YearFraction(sign * numerator / g, sign * denominator / g)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

private const val BASIS_360 = 360L
private const val BASIS_365 = 365L
private const val DAYS_PER_30_360_MONTH = 30
private const val DAY_31 = 31
private const val DAY_29 = 29

/**
 * Day-count conventions — how an accrual period `[start, end)` is turned into a fraction of a
 * year. Definitions follow the 2006 ISDA Definitions, Section 4.16 ("Day Count Fraction");
 * [THIRTY_360_US] follows the SIA "Standard Securities Calculation Methods" 30/360 rule (the
 * US variant with the end-of-February adjustment, sometimes called 30U/360).
 *
 * Pure domain primitive: no framework dependency. `yearFraction(start, end)` for `end < start` is
 * the negation of `yearFraction(end, start)`; a zero-length period is [YearFraction.ZERO].
 */
enum class DayCount {
    /** ISDA 4.16(e) "Actual/360": actual days / 360. */
    ACT_360 {
        override fun forward(start: LocalDate, end: LocalDate) = YearFraction.of(actualDays(start, end), BASIS_360)
    },

    /** ISDA 4.16(d) "Actual/365 (Fixed)": actual days / 365, leap years included. */
    ACT_365_FIXED {
        override fun forward(start: LocalDate, end: LocalDate) = YearFraction.of(actualDays(start, end), BASIS_365)
    },

    /**
     * ISDA 4.16(b) "Actual/Actual (ISDA)": days falling in a leap year / 366 plus days falling in a
     * non-leap year / 365 (the period's first day counts, its last does not).
     */
    ACT_ACT_ISDA {
        override fun forward(start: LocalDate, end: LocalDate): YearFraction {
            var total = YearFraction.ZERO
            var cursor = start
            while (cursor < end) {
                val nextYear = LocalDate.of(cursor.year + 1, 1, 1)
                val segmentEnd = if (end < nextYear) end else nextYear
                total += YearFraction.of(actualDays(cursor, segmentEnd), Year.of(cursor.year).length().toLong())
                cursor = segmentEnd
            }
            return total
        }
    },

    /**
     * ISDA 4.16(f) "30/360" (Bond Basis): D1 = 31 becomes 30; D2 = 31 becomes 30 only when D1
     * (after adjustment) is greater than 29. No February adjustment.
     */
    THIRTY_360_BOND_BASIS {
        override fun forward(start: LocalDate, end: LocalDate): YearFraction {
            val d1 = if (start.dayOfMonth == DAY_31) DAYS_PER_30_360_MONTH else start.dayOfMonth
            val d2 = if (end.dayOfMonth == DAY_31 && d1 > DAY_29) DAYS_PER_30_360_MONTH else end.dayOfMonth
            return thirty360(start, d1, end, d2)
        }
    },

    /**
     * 30/360 US (SIA): if D1 and D2 are both the last day of February, D2 = 30; if D1 is the last
     * day of February, D1 = 30; if D2 = 31 and D1 >= 30, D2 = 30; if D1 = 31, D1 = 30.
     */
    THIRTY_360_US {
        override fun forward(start: LocalDate, end: LocalDate): YearFraction {
            var d1 = start.dayOfMonth
            var d2 = end.dayOfMonth
            if (isLastDayOfFebruary(start) && isLastDayOfFebruary(end)) d2 = DAYS_PER_30_360_MONTH
            if (isLastDayOfFebruary(start)) d1 = DAYS_PER_30_360_MONTH
            if (d2 == DAY_31 && d1 >= DAYS_PER_30_360_MONTH) d2 = DAYS_PER_30_360_MONTH
            if (d1 == DAY_31) d1 = DAYS_PER_30_360_MONTH
            return thirty360(start, d1, end, d2)
        }
    },

    /** ISDA 4.16(g) "30E/360" (Eurobond Basis): D1 = 31 becomes 30; D2 = 31 becomes 30. */
    THIRTY_E_360 {
        override fun forward(start: LocalDate, end: LocalDate): YearFraction {
            val d1 = if (start.dayOfMonth == DAY_31) DAYS_PER_30_360_MONTH else start.dayOfMonth
            val d2 = if (end.dayOfMonth == DAY_31) DAYS_PER_30_360_MONTH else end.dayOfMonth
            return thirty360(start, d1, end, d2)
        }
    },
    ;

    /** Fraction of a year for `[start, end)`; negative when [end] precedes [start]. */
    fun yearFraction(start: LocalDate, end: LocalDate): YearFraction = when {
        start == end -> YearFraction.ZERO
        end < start -> -forward(end, start)
        else -> forward(start, end)
    }

    protected abstract fun forward(start: LocalDate, end: LocalDate): YearFraction

    protected fun actualDays(start: LocalDate, end: LocalDate): Long = ChronoUnit.DAYS.between(start, end)

    protected fun isLastDayOfFebruary(d: LocalDate): Boolean =
        d.monthValue == java.time.Month.FEBRUARY.value && d.dayOfMonth == d.lengthOfMonth()

    /** ISDA 4.16(f)/(g): [360 × (Y2 − Y1) + 30 × (M2 − M1) + (D2 − D1)] / 360. */
    protected fun thirty360(start: LocalDate, d1: Int, end: LocalDate, d2: Int): YearFraction = YearFraction.of(
        BASIS_360 * (end.year - start.year) +
            DAYS_PER_30_360_MONTH.toLong() * (end.monthValue - start.monthValue) + (d2 - d1),
        BASIS_360,
    )
}
