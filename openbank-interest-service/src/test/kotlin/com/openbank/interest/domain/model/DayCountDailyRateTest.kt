// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * One test per product day-count convention. Before the kernel [DayCount.convention] mapping the
 * service computed `annualRate / (if ACT_360 then 360 else 365)`, so ACT_ACT and THIRTY_360 were
 * silently ACT/365. Expected values are derived by hand: 5 % p.a. on 100 000.
 */
class DayCountDailyRateTest {

    private val rate = BigDecimal("0.05")
    private val balance = BigDecimal("100000")
    private fun accrued(dc: DayCount, date: String) =
        balance.multiply(dc.dailyRate(rate, LocalDate.parse(date))).setScale(6, RoundingMode.HALF_UP)

    @Test
    fun `ACT_365 is unchanged - 0_05 over 365`() {
        // 0.05/365 = 0.000136986301... -> 0.0001369863; x 100000 = 13.698630
        assertThat(DayCount.ACT_365.dailyRate(rate, LocalDate.parse("2024-06-15"))).isEqualByComparingTo("0.0001369863")
        assertThat(accrued(DayCount.ACT_365, "2024-06-15")).isEqualByComparingTo("13.698630")
    }

    @Test
    fun `ACT_360 is unchanged - 0_05 over 360`() {
        // 0.05/360 = 0.000138888... -> 0.0001388889; x 100000 = 13.888890
        assertThat(accrued(DayCount.ACT_360, "2024-06-15")).isEqualByComparingTo("13.888890")
    }

    @Test
    fun `ACT_ACT uses 366 in a leap year and 365 otherwise`() {
        // Before: 13.698630 every day. After, leap 2024: 0.05/366 = 0.000136612021... -> 0.0001366120
        // x 100000 = 13.661200. Non-leap 2025: 0.05/365 -> 13.698630 (unchanged).
        assertThat(accrued(DayCount.ACT_ACT, "2024-06-15")).isEqualByComparingTo("13.661200")
        assertThat(accrued(DayCount.ACT_ACT, "2025-06-15")).isEqualByComparingTo("13.698630")
    }

    @Test
    fun `THIRTY_360 books 30 days per month`() {
        // Before: 13.698630 every day. After (30/360 Bond Basis, ISDA 4.16(f)):
        //  ordinary day:      1/360 -> 0.0001388889 -> 13.888890
        //  Jan 30 -> Jan 31:  D1 = 30, D2 = 31 -> 30 (D1 > 29): 30 - 30 = 0 days -> 0
        //  Jan 31 -> Feb 1:   D1 = 31 -> 30: 30 + (1 - 30) = 1 day -> 13.888890
        //  Feb 28 2025:       Feb 28 -> Mar 1 = 30 + (1 - 28) = 3 days -> 3 x 0.05/360 = 0.0004166667 -> 41.666670
        assertThat(accrued(DayCount.THIRTY_360, "2025-06-15")).isEqualByComparingTo("13.888890")
        assertThat(accrued(DayCount.THIRTY_360, "2025-01-30")).isEqualByComparingTo("0")
        assertThat(accrued(DayCount.THIRTY_360, "2025-01-31")).isEqualByComparingTo("13.888890")
        assertThat(accrued(DayCount.THIRTY_360, "2025-02-28")).isEqualByComparingTo("41.666670")
        // A whole month sums to 30/360 of a year: 30 x 13.888890 = 416.666700 for January 2025.
        val jan = (1..31).map { accrued(DayCount.THIRTY_360, "2025-01-%02d".format(it)) }.reduce(BigDecimal::add)
        assertThat(jan).isEqualByComparingTo("416.666700")
    }
}
