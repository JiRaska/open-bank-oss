// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.WEEKENDS
import com.openbank.libs.finance.bd
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.fixedLeg
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.pricing.DiscountCurve
import com.openbank.libs.finance.pricing.LegPricer
import com.openbank.libs.finance.pricing.PRICING_MC
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@OptIn(ExperimentalKotest::class)
class SchedulePropertyTest {
    private val config = PropTestConfig(seed = 20261019L, iterations = 400)
    private val epoch = LocalDate.of(2020, 1, 1)
    private val frequencies = listOf(
        Frequency.Zero, Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.SEMI_ANNUAL, Frequency.ANNUAL,
        Frequency.Months(2), Frequency.Weeks(1), Frequency.Weeks(2), Frequency.Days(10),
    )

    @Test
    fun `schedules are contiguous, ordered and cover the period exactly`(): Unit = runBlocking {
        checkAll(
            config,
            Arb.long(0L, 3_650L),
            Arb.int(1, 3_000),
            Arb.element(frequencies),
            Arb.enum<StubType>(),
            Arb.boolean(),
            Arb.boolean(),
        ) { offset, length, frequency, stub, eom, adjust ->
            val start = epoch.plusDays(offset)
            val end = start.plusDays(length.toLong())
            val leg = fixedLeg(
                start.toString(),
                end.toString(),
                frequency,
                stub = stub,
                dates = DateRules(TARGET2, DateRoll.MODIFIED_FOLLOWING, adjustAccrualPeriod = adjust),
            )
                .let { it.copy(schedule = it.schedule.copy(endOfMonth = eom)) }
            val periods = ScheduleGenerator.generate(leg).periods
            assertThat(periods.first().unadjustedStart).isEqualTo(start)
            assertThat(periods.last().unadjustedEnd).isEqualTo(end)
            periods.zipWithNext().forEach { (a, b) ->
                assertThat(b.unadjustedStart).isEqualTo(a.unadjustedEnd)
                assertThat(b.accrualStart).isEqualTo(a.accrualEnd)
                assertThat(b.paymentDate).isAfterOrEqualTo(a.paymentDate)
            }
            periods.forEach {
                assertThat(it.unadjustedEnd).isAfter(it.unadjustedStart)
                assertThat(it.accrualEnd).isAfter(it.accrualStart)
                assertThat(TARGET2.isBusinessDay(it.paymentDate)).isTrue()
            }
            assertThat(periods.map { it.index }).isEqualTo(periods.indices.toList())
        }
    }

    @Test
    fun `ACT-360 year fractions of an unadjusted schedule sum to the whole period`(): Unit = runBlocking {
        checkAll(config, Arb.long(0L, 3_650L), Arb.int(1, 3_000), Arb.element(frequencies), Arb.enum<StubType>()) {
                offset,
                length,
                f,
                stub,
            ->
            val start = epoch.plusDays(offset)
            val end = start.plusDays(length.toLong())
            val leg =
                fixedLeg(
                    start.toString(),
                    end.toString(),
                    f,
                    stub = stub,
                    dates = DateRules(WEEKENDS, DateRoll.UNADJUSTED),
                )
            val sum = ScheduleGenerator.generate(leg).periods.fold(YearFraction.ZERO) { acc, p -> acc + p.yearFraction }
            assertThat(sum).isEqualTo(DayCount.ACT_360.yearFraction(start, end))
        }
    }

    @Test
    fun `every amortisation profile repays the notional exactly`(): Unit = runBlocking {
        checkAll(
            config,
            Arb.long(1L, 999_999_999L),
            Arb.int(1, 40),
            Arb.element(listOf(AmortisationSpec.Linear, AmortisationSpec.Annuity)),
        ) {
                cents,
                quarters,
                amort,
            ->
            val notional = BigDecimal.valueOf(cents, 2).max(bd("0.01"))
            val leg = fixedLeg(
                "2026-01-15",
                LocalDate.of(2026, 1, 15).plusMonths(3L * quarters).toString(),
                rate = RateSpec.Fixed(bd("0.045")),
                notional = NotionalSpec(notional, amort),
            )
            val principal = CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete()
                .filter { it.kind == CashFlowKind.PRINCIPAL }
                .fold(BigDecimal.ZERO) { a, f -> a + f.amount }
            assertThat(principal).isEqualByComparingTo(notional)
        }
    }

    /**
     * A bullet bond whose annual 30E/360 coupon equals the yield, discounted at that yield with
     * annual compounding on the coupon grid, prices at par to the cent.
     */
    @Test
    fun `a par bond prices at par at its own yield`(): Unit = runBlocking {
        checkAll(config, Arb.int(1, 30), Arb.int(1, 1_500), Arb.long(0L, 3_000L)) { years, bp, offset ->
            val y = BigDecimal.valueOf(bp.toLong(), 4)
            val raw = epoch.plusDays(offset)
            // Day <= 28: an anniversary grid off the 29th-31st is not whole 30E/360 years.
            val start = raw.withDayOfMonth(minOf(raw.dayOfMonth, 28))
            val leg = fixedLeg(
                start.toString(),
                start.plusYears(years.toLong()).toString(),
                Frequency.ANNUAL,
                rate = RateSpec.Fixed(y),
                notional = NotionalSpec(bd("1000000")),
                dates = DateRules(WEEKENDS, DateRoll.FOLLOWING, adjustAccrualPeriod = false, adjustPaymentDate = false),
                dayCount = DayCount.THIRTY_E_360,
            )
            val curve = object : DiscountCurve {
                override val valuationDate: LocalDate = start
                override fun discountFactor(date: LocalDate): BigDecimal {
                    val k = DayCount.THIRTY_E_360.yearFraction(start, date).toBigDecimal(PRICING_MC).intValueExact()
                    return BigDecimal.ONE.divide((BigDecimal.ONE + y).pow(k, PRICING_MC), PRICING_MC)
                }
            }
            val pv = LegPricer.presentValue(
                CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete(),
                curve,
            )
            assertThat(pv.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("1000000.00")
        }
    }
}
