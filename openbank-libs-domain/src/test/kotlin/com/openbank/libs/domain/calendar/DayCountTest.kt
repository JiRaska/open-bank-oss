// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.localDate
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * [DayCount] against the 2006 ISDA Definitions §4.16 formulas (and SIA 30/360 US). Every expected
 * value is derived by hand in the comment next to it from the formula quoted in [DayCount]'s KDoc —
 * none is copied from an implementation.
 */
class DayCountTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun yf(dc: DayCount, a: String, b: String) = dc.yearFraction(d(a), d(b))
    private fun frac(n: Long, den: Long) = YearFraction.of(n, den)

    // --- reference table ----------------------------------------------------------------------

    @Test
    fun `ACT_ACT_ISDA splits a period across a leap-year boundary`() {
        // ISDA "EMU and Market Conventions: Recent Developments" (1998) §4 worked example,
        // 2003-11-01 -> 2004-05-01. By hand: 2003-11-01..2004-01-01 = 30 (Nov) + 31 (Dec) = 61 days
        // in non-leap 2003; 2004-01-01..2004-05-01 = 31 + 29 + 31 + 30 = 121 days in leap 2004.
        // 61/365 + 121/366 = (61*366 + 121*365) / (365*366) = 66491 / 133590.
        assertThat(yf(DayCount.ACT_ACT_ISDA, "2003-11-01", "2004-05-01")).isEqualTo(frac(66491, 133590))
    }

    @Test
    fun `ACT_360 and ACT_365_FIXED count actual days`() {
        // Same period, 182 actual days (61 + 121). ISDA 4.16(e): 182/360 = 91/180; 4.16(d): 182/365.
        assertThat(yf(DayCount.ACT_360, "2003-11-01", "2004-05-01")).isEqualTo(frac(91, 180))
        assertThat(yf(DayCount.ACT_365_FIXED, "2003-11-01", "2004-05-01")).isEqualTo(frac(182, 365))
    }

    @Test
    fun `leap years - 2024-02-29 exists, 2100 is not a leap year`() {
        // 2024 is leap: one day in it is 1/366 under ACT/ACT, whole year = 366/366 = 1.
        assertThat(yf(DayCount.ACT_ACT_ISDA, "2024-02-29", "2024-03-01")).isEqualTo(frac(1, 366))
        assertThat(yf(DayCount.ACT_ACT_ISDA, "2024-01-01", "2025-01-01")).isEqualTo(frac(1, 1))
        // 2100 is divisible by 100 but not 400: 365 days, Feb 28 -> Mar 1 is ONE day, 1/365.
        assertThat(yf(DayCount.ACT_ACT_ISDA, "2100-02-28", "2100-03-01")).isEqualTo(frac(1, 365))
        assertThat(yf(DayCount.ACT_ACT_ISDA, "2100-01-01", "2101-01-01")).isEqualTo(frac(1, 1))
        // ACT/365F does not care: 2024 whole year = 366/365.
        assertThat(yf(DayCount.ACT_365_FIXED, "2024-01-01", "2025-01-01")).isEqualTo(frac(366, 365))
    }

    @Test
    fun `30-360 variants - end-of-month rules Jan 31 to Feb to Mar 31`() {
        // Formula: [360(Y2-Y1) + 30(M2-M1) + (D2-D1)] / 360.
        // Jan 31 -> Feb 28 2025: every variant sets D1 = 30 (31 -> 30); D2 = 28 unchanged
        // (US February rule needs D1 to be end-Feb). 30*1 + (28-30) = 28.
        for (dc in listOf(DayCount.THIRTY_360_BOND_BASIS, DayCount.THIRTY_360_US, DayCount.THIRTY_E_360)) {
            assertThat(yf(dc, "2025-01-31", "2025-02-28")).`as`(dc.name).isEqualTo(frac(28, 360))
        }
        // Feb 28 2025 -> Mar 31 2025:
        //  Bond basis: D1 = 28 (no Feb rule), D2 = 31 kept because D1 <= 29: 30 + 3 = 33.
        //  US:         D1 end-Feb -> 30, then D2 = 31 with D1 >= 30 -> 30: 30 + 0 = 30.
        //  30E/360:    D1 = 28, D2 = 31 -> 30: 30 + 2 = 32.
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2025-02-28", "2025-03-31")).isEqualTo(frac(33, 360))
        assertThat(yf(DayCount.THIRTY_360_US, "2025-02-28", "2025-03-31")).isEqualTo(frac(30, 360))
        assertThat(yf(DayCount.THIRTY_E_360, "2025-02-28", "2025-03-31")).isEqualTo(frac(32, 360))
        // Leap Feb 29 2024 -> Mar 31 2024: Bond 30 + (31-29) = 32; US D1 -> 30, D2 -> 30: 30;
        // 30E D2 -> 30: 30 + 1 = 31.
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2024-02-29", "2024-03-31")).isEqualTo(frac(32, 360))
        assertThat(yf(DayCount.THIRTY_360_US, "2024-02-29", "2024-03-31")).isEqualTo(frac(30, 360))
        assertThat(yf(DayCount.THIRTY_E_360, "2024-02-29", "2024-03-31")).isEqualTo(frac(31, 360))
        // US both-end-Feb rule: Feb 28 2025 -> Feb 29 2028: D1 -> 30, D2 -> 30: 360*3 = 1080.
        assertThat(yf(DayCount.THIRTY_360_US, "2025-02-28", "2028-02-29")).isEqualTo(frac(1080, 360))
        // Bond basis differs from 30E only when D1 < 30 and D2 = 31: Mar 15 -> May 31 2025:
        // Bond 60 + (31-15) = 76; 30E 60 + (30-15) = 75.
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2025-03-15", "2025-05-31")).isEqualTo(frac(76, 360))
        assertThat(yf(DayCount.THIRTY_E_360, "2025-03-15", "2025-05-31")).isEqualTo(frac(75, 360))
    }

    @Test
    fun `30-360 one-day periods sum to 30 per month`() {
        // Bond basis, Jan 30 -> 31: D1 = 30, D2 = 31 -> 30: 0 days. Jan 31 -> Feb 1: D1 = 30: 30 + (1-30) = 1.
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2025-01-30", "2025-01-31")).isEqualTo(YearFraction.ZERO)
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2025-01-31", "2025-02-01")).isEqualTo(frac(1, 360))
        // Feb 28 -> Mar 1 2025: 30 + (1-28) = 3 days.
        assertThat(yf(DayCount.THIRTY_360_BOND_BASIS, "2025-02-28", "2025-03-01")).isEqualTo(frac(3, 360))
    }

    @Test
    fun `applyTo rounds once`() {
        // 100000 * 0.05 * 1/366 = 13.66120218... -> 13.661202 at scale 6 HALF_UP.
        val v = frac(1, 366).applyTo(BigDecimal("5000.00"), 6, RoundingMode.HALF_UP)
        assertThat(v).isEqualByComparingTo("13.661202")
    }

    // --- properties ---------------------------------------------------------------------------

    private val dateArb = Arb.localDate(LocalDate.of(1990, 1, 1), LocalDate.of(2110, 12, 31))
    private val allArb = Arb.element(DayCount.entries)
    private val actualArb = Arb.element(DayCount.ACT_360, DayCount.ACT_365_FIXED, DayCount.ACT_ACT_ISDA)

    @Test
    fun `zero-length period is zero and reversing the dates negates`(): Unit = runBlocking {
        checkAll(allArb, dateArb, dateArb) { dc, a, b ->
            assertThat(dc.yearFraction(a, a)).isEqualTo(YearFraction.ZERO)
            assertThat(dc.yearFraction(b, a)).isEqualTo(-dc.yearFraction(a, b))
            val sign = a.compareTo(b).coerceIn(-1, 1)
            if (dc in listOf(DayCount.ACT_360, DayCount.ACT_365_FIXED, DayCount.ACT_ACT_ISDA)) {
                assertThat(java.lang.Long.signum(dc.yearFraction(a, b).numerator)).isEqualTo(-sign)
            }
        }
    }

    @Test
    fun `actual conventions are additive over adjacent periods`(): Unit = runBlocking {
        checkAll(actualArb, dateArb, dateArb, dateArb) { dc, x, y, z ->
            val (a, b, c) = listOf(x, y, z).sorted()
            assertThat(dc.yearFraction(a, b) + dc.yearFraction(b, c)).isEqualTo(dc.yearFraction(a, c))
        }
    }

    @Test
    fun `ACT_ACT_ISDA of any whole calendar year is exactly one`(): Unit = runBlocking {
        checkAll(Arb.element((1900..2200).toList())) { y ->
            assertThat(DayCount.ACT_ACT_ISDA.yearFraction(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1)))
                .isEqualTo(frac(1, 1))
        }
    }

    @Test
    fun `30-360 variants count a same-day-of-month year step as exactly one`(): Unit = runBlocking {
        val thirty = Arb.element(DayCount.THIRTY_360_BOND_BASIS, DayCount.THIRTY_360_US, DayCount.THIRTY_E_360)
        checkAll(thirty, dateArb) { dc, a ->
            val b = a.plusYears(1)
            // SIA 30/360 US end-of-February rule is asymmetric: a non-leap Feb 28 is the LAST day of
            // February (D1 -> 30) but the same date one year on, in a leap year, is not (D2 stays 28),
            // so that single step is 358/360 by definition. Bond Basis and 30E/360 have no Feb rule.
            val nonLeapFeb28ToLeap = a.monthValue == 2 && a.dayOfMonth == 28 && !a.isLeapYear && b.isLeapYear
            val usFebAsymmetry = dc == DayCount.THIRTY_360_US && nonLeapFeb28ToLeap
            if (a.dayOfMonth <= 28 && !usFebAsymmetry) {
                assertThat(dc.yearFraction(a, b)).isEqualTo(frac(1, 1))
            }
        }
    }

    @Test
    fun `30-360 US non-leap Feb 28 to leap Feb 28 is 358 over 360 by the end-of-February rule`() {
        val a = LocalDate.of(2027, 2, 28)
        val b = LocalDate.of(2028, 2, 28)
        assertThat(DayCount.THIRTY_360_US.yearFraction(a, b)).isEqualTo(frac(358, 360))
        assertThat(DayCount.THIRTY_360_BOND_BASIS.yearFraction(a, b)).isEqualTo(frac(1, 1))
        assertThat(DayCount.THIRTY_E_360.yearFraction(a, b)).isEqualTo(frac(1, 1))
        // reverse direction: leap Feb 28 is not last day, non-leap Feb 28 is -> D1=28, D2=28 -> 360
        assertThat(DayCount.THIRTY_360_US.yearFraction(LocalDate.of(2028, 2, 28), LocalDate.of(2029, 2, 28)))
            .isEqualTo(frac(1, 1))
    }
}
