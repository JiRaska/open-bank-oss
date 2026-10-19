// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.WEEKENDS
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.d
import com.openbank.libs.finance.fixedLeg
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.ScheduleSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ScheduleGeneratorTest {
    private fun grid(start: String, end: String, f: Frequency, stub: StubType, eom: Boolean = false) =
        DateGrid.boundaries(d(start), d(end), f, stub, eom).map { it.toString() }

    @Test
    fun `stub placement - short and long, initial and final`() {
        val q = Frequency.QUARTERLY
        assertThat(grid("2026-01-15", "2026-12-01", q, StubType.SHORT_INITIAL))
            .containsExactly("2026-01-15", "2026-03-01", "2026-06-01", "2026-09-01", "2026-12-01")
        assertThat(grid("2026-01-15", "2026-12-01", q, StubType.LONG_INITIAL))
            .containsExactly("2026-01-15", "2026-06-01", "2026-09-01", "2026-12-01")
        assertThat(grid("2026-01-15", "2026-12-01", q, StubType.SHORT_FINAL))
            .containsExactly("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15", "2026-12-01")
        assertThat(grid("2026-01-15", "2026-12-01", q, StubType.LONG_FINAL))
            .containsExactly("2026-01-15", "2026-04-15", "2026-07-15", "2026-12-01")
    }

    @Test
    fun `no stub when the period divides evenly, and a lone short period is never merged away`() {
        assertThat(grid("2026-01-15", "2027-01-15", Frequency.SEMI_ANNUAL, StubType.LONG_FINAL))
            .containsExactly("2026-01-15", "2026-07-15", "2027-01-15")
        assertThat(grid("2026-01-15", "2026-02-01", Frequency.QUARTERLY, StubType.LONG_INITIAL))
            .containsExactly("2026-01-15", "2026-02-01")
        assertThat(grid("2026-01-15", "2026-02-01", Frequency.Zero, StubType.LONG_INITIAL))
            .containsExactly("2026-01-15", "2026-02-01")
    }

    @Test
    fun `end-of-month rule keeps month ends, anchor stepping keeps the 31st`() {
        assertThat(grid("2026-02-28", "2026-06-30", Frequency.MONTHLY, StubType.SHORT_FINAL, eom = true))
            .containsExactly("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30")
        // Without EOM the 28th stays the 28th; the trailing [Jun 28, Jun 30) is a short final stub.
        assertThat(grid("2026-02-28", "2026-06-30", Frequency.MONTHLY, StubType.SHORT_FINAL))
            .containsExactly("2026-02-28", "2026-03-28", "2026-04-28", "2026-05-28", "2026-06-28", "2026-06-30")
        // Anchor stepping: Jan 31 + 1M = Feb 28, but + 2M = Mar 31 (not Mar 28).
        assertThat(grid("2026-01-31", "2026-04-30", Frequency.MONTHLY, StubType.SHORT_FINAL))
            .containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30")
        // Backward with EOM from a month-end maturity, over a leap February.
        assertThat(grid("2023-11-30", "2024-05-31", Frequency.QUARTERLY, StubType.SHORT_INITIAL, eom = true))
            .containsExactly("2023-11-30", "2024-02-29", "2024-05-31")
    }

    @Test
    fun `weekly and daily frequencies`() {
        assertThat(grid("2026-03-02", "2026-03-20", Frequency.Weeks(1), StubType.SHORT_FINAL))
            .containsExactly("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-20")
        assertThat(grid("2026-03-02", "2026-03-12", Frequency.Days(5), StubType.SHORT_INITIAL))
            .containsExactly("2026-03-02", "2026-03-07", "2026-03-12")
        assertThat(Frequency.Weeks(2).token).isEqualTo("2W")
        assertThat(Frequency.Days(7).token).isEqualTo("7D")
        assertThatThrownBy { Frequency.Months(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Frequency.Weeks(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Frequency.Days(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            Frequency.Zero.step(d("2026-01-01"), 1)
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    /**
     * 15 Feb and 15 Mar 2026 are Sundays. Unadjusted accrual runs to the Sunday while the payment
     * rolls to Monday; adjusted accrual runs to the Monday itself.
     */
    @Test
    fun `unadjusted accrual accrues to Sunday, adjusted accrual to Monday`() {
        val unadjusted = ScheduleGenerator.generate(
            fixedLeg(
                "2026-01-15",
                "2026-03-15",
                Frequency.MONTHLY,
                dates = DateRules(TARGET2, DateRoll.FOLLOWING, adjustAccrualPeriod = false),
            ),
        )
        val adjusted = ScheduleGenerator.generate(
            fixedLeg(
                "2026-01-15",
                "2026-03-15",
                Frequency.MONTHLY,
                dates = DateRules(TARGET2, DateRoll.FOLLOWING, adjustAccrualPeriod = true),
            ),
        )
        val u0 = unadjusted.periods[0]
        assertThat(u0.accrualEnd).isEqualTo(d("2026-02-15"))
        assertThat(u0.paymentDate).isEqualTo(d("2026-02-16"))
        assertThat(u0.yearFraction).isEqualTo(YearFraction.of(31, 360))
        val a0 = adjusted.periods[0]
        assertThat(a0.accrualEnd).isEqualTo(d("2026-02-16"))
        assertThat(a0.paymentDate).isEqualTo(d("2026-02-16"))
        assertThat(a0.yearFraction).isEqualTo(YearFraction.of(32, 360))
        assertThat(adjusted.periods[1].accrual).isEqualTo(DateRange(d("2026-02-16"), d("2026-03-16")))
        // 1,000,000 x 3.6 % x 31/360 = 3,100.00 vs x 32/360 = 3,200.00.
        assertThat(
            CashFlowProjector.project(unadjusted).requireComplete().first().amount,
        ).isEqualByComparingTo("3100.00")
        assertThat(CashFlowProjector.project(adjusted).requireComplete().first().amount).isEqualByComparingTo("3200.00")
    }

    @Test
    fun `payment not adjusted stays on Sunday, payment lag counts business days`() {
        val noAdj = ScheduleGenerator.generate(
            fixedLeg(
                "2026-01-15",
                "2026-03-15",
                Frequency.MONTHLY,
                dates = DateRules(TARGET2, DateRoll.FOLLOWING, adjustAccrualPeriod = false, adjustPaymentDate = false),
            ),
        )
        assertThat(noAdj.periods[0].paymentDate).isEqualTo(d("2026-02-15"))
        val lagged = ScheduleGenerator.generate(
            fixedLeg(
                "2026-01-15",
                "2026-03-15",
                Frequency.MONTHLY,
                dates = DateRules(TARGET2, DateRoll.FOLLOWING, paymentLagBusinessDays = 2),
            ),
        )
        // Sun 15 Feb -> Mon 16 Feb, + 2 business days -> Wed 18 Feb.
        assertThat(lagged.periods[0].paymentDate).isEqualTo(d("2026-02-18"))
        assertThatThrownBy {
            DateRules(TARGET2, paymentLagBusinessDays = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `initial exchange date is the rolled start`() {
        assertThat(
            ScheduleGenerator.generate(fixedLeg("2026-03-15", "2026-06-15")).initialExchangeDate,
        ).isEqualTo(d("2026-03-16"))
    }

    @Test
    fun `payment frequency aggregates accrual periods`() {
        val leg = fixedLeg("2026-01-15", "2027-01-15", Frequency.QUARTERLY).let {
            it.copy(schedule = it.schedule.copy(paymentFrequency = Frequency.SEMI_ANNUAL))
        }
        val periods = ScheduleGenerator.generate(leg).periods
        assertThat(periods.map { it.paymentDate.toString() })
            .containsExactly("2026-07-15", "2026-07-15", "2027-01-15", "2027-01-15")
        assertThat(CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete().map { it.kind })
            .containsOnly(CashFlowKind.INTEREST)
    }

    @Test
    fun `payment boundaries off the accrual grid are rejected`() {
        val leg = fixedLeg("2026-01-15", "2027-01-15", Frequency.Months(5)).let {
            it.copy(schedule = it.schedule.copy(paymentFrequency = Frequency.SEMI_ANNUAL))
        }
        assertThatThrownBy { ScheduleGenerator.generate(leg) }.hasMessageContaining("not a subset")
    }

    @Test
    fun `a period that rolls to zero length merges into its neighbour`() {
        // Daily accrual Fri 6 -> Tue 10 Mar 2026: Sat 7, Sun 8 and Mon 9 all adjust to Mon 9.
        val periods = ScheduleGenerator.generate(
            fixedLeg("2026-03-06", "2026-03-10", Frequency.Days(1), dates = DateRules(WEEKENDS, DateRoll.FOLLOWING)),
        ).periods
        assertThat(periods.map { it.accrual }).containsExactly(
            DateRange(d("2026-03-06"), d("2026-03-09")),
            DateRange(d("2026-03-09"), d("2026-03-10")),
        )
        // A one-day stub Sat -> Sun at maturity merges into the previous period instead.
        val atEnd = ScheduleGenerator.generate(
            fixedLeg(
                "2026-03-02",
                "2026-03-08",
                Frequency.Days(5),
                stub = StubType.SHORT_FINAL,
                dates = DateRules(WEEKENDS, DateRoll.FOLLOWING),
            ),
        ).periods
        assertThat(atEnd.map { it.unadjusted }).containsExactly(DateRange(d("2026-03-02"), d("2026-03-08")))
        assertThat(atEnd.single().accrual).isEqualTo(DateRange(d("2026-03-02"), d("2026-03-09")))
        // A whole Saturday -> Sunday deal cannot be adjusted at all.
        assertThatThrownBy {
            ScheduleGenerator.generate(
                fixedLeg("2026-03-07", "2026-03-08", Frequency.Zero, dates = DateRules(WEEKENDS, DateRoll.FOLLOWING)),
            )
        }.hasMessageContaining("collapsed the whole schedule")
    }

    @Test
    fun `schedule spec rejects an inverted period`() {
        assertThatThrownBy { ScheduleSpec(d("2026-02-01"), d("2026-01-01"), Frequency.MONTHLY) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DateRange(d("2026-02-01"), d("2026-01-01"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DateGrid.boundaries(d("2026-02-01"), d("2026-02-01"), Frequency.MONTHLY, StubType.SHORT_FINAL, false)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /** Hand-computed year fractions for every day count on the edge cases that separate them. */
    @ParameterizedTest(name = "{0} [{1}, {2}) = {3}/{4}")
    @CsvSource(
        "ACT_360, 2024-01-31, 2024-02-29, 29, 360",
        "ACT_365_FIXED, 2024-02-28, 2024-03-01, 2, 365",
        // 17 days in 2023 (/365) + 166 days in 2024 (/366) = 17/365 + 166/366.
        "ACT_ACT_ISDA, 2023-12-15, 2024-06-15, 33406, 66795",
        // 30/360 bond basis: D1 31 -> 30; D2 31 -> 30 because D1 > 29: 30 x 1 + 0 = 30.
        "THIRTY_360_BOND_BASIS, 2026-01-31, 2026-03-31, 60, 360",
        // Bond basis has no February rule: Feb 28 -> Mar 31: D2 stays 31 (D1 = 28): 30 + 3 = 33.
        "THIRTY_360_BOND_BASIS, 2026-02-28, 2026-03-31, 33, 360",
        // 30E/360: D2 31 -> 30 always: Feb 28 -> Mar 31 = 30 + 2 = 32.
        "THIRTY_E_360, 2026-02-28, 2026-03-31, 32, 360",
        // 30/360 US: D1 last of Feb -> 30; D2 31 with D1 >= 30 -> 30: 30 + 0 = 30.
        "THIRTY_360_US, 2026-02-28, 2026-03-31, 30, 360",
    )
    fun `period year fraction follows the leg day count`(
        dc: DayCount,
        start: String,
        end: String,
        num: Long,
        den: Long,
    ) {
        val leg = fixedLeg(start, end, Frequency.Zero, dates = DateRules(WEEKENDS, DateRoll.UNADJUSTED), dayCount = dc)
        assertThat(ScheduleGenerator.generate(leg).periods.single().yearFraction).isEqualTo(YearFraction.of(num, den))
    }
}
