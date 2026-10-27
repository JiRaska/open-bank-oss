// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.cashflow

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.finance.CERTIS
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.bd
import com.openbank.libs.finance.d
import com.openbank.libs.finance.fixedLeg
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.CapFloorAppliesTo
import com.openbank.libs.finance.leg.FixingConvention
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.leg.RateBounds
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.pricing.ForwardCurve
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CashFlowProjectorTest {
    private val index = "EURIBOR-3M"

    private fun floatingLeg(
        fixingCalendar: com.openbank.libs.finance.calendar.FinanceCalendar = TARGET2,
        inArrears: Boolean = false,
        rateUntilFirstFixing: BigDecimal? = null,
        bounds: RateBounds = RateBounds.NONE,
    ): LegSpec = fixedLeg(
        "2026-04-07",
        "2026-10-07",
        rate = RateSpec.Floating(
            index = index,
            spread = bd("0.001"),
            fixing = FixingConvention(2, fixingCalendar, inArrears),
            resetFrequency = Frequency.QUARTERLY,
            bounds = bounds,
            rateUntilFirstFixing = rateUntilFirstFixing,
        ),
    )

    private val flat3 = ForwardCurve { _, _, _ -> bd("0.03") }

    @Test
    fun `fixing date is T-2 on the fixing calendar across holidays`() {
        val periods = ScheduleGenerator.generate(floatingLeg()).periods
        // Tue 7 Apr 2026: Mon 6 (Easter Monday) and Fri 3 (Good Friday) closed -> Wed 1 Apr.
        assertThat(periods[0].fixingDate).isEqualTo(d("2026-04-01"))
        // Tue 7 Jul 2026 on TARGET2 -> Fri 3 Jul.
        assertThat(periods[1].fixingDate).isEqualTo(d("2026-07-03"))
        // On CERTIS Mon 6 Jul (Jan Hus Day) is closed too -> Thu 2 Jul.
        assertThat(ScheduleGenerator.generate(floatingLeg(CERTIS)).periods[1].fixingDate).isEqualTo(d("2026-07-02"))
        // In arrears the reference is the accrual END: period 0 fixes on Fri 3 Jul.
        assertThat(
            ScheduleGenerator.generate(floatingLeg(inArrears = true)).periods[0].fixingDate,
        ).isEqualTo(d("2026-07-03"))
        assertThat(ScheduleGenerator.generate(fixedLeg("2026-04-07", "2026-10-07")).periods[0].fixingDate).isNull()
    }

    @Test
    fun `zero fixing lag on a holiday rolls to the preceding business day`() {
        val leg = fixedLeg(
            "2026-04-06",
            "2026-07-06",
            rate = RateSpec.Floating(
                index,
                fixing = FixingConvention(0, TARGET2),
                resetFrequency = Frequency.QUARTERLY,
            ),
            dates = com.openbank.libs.finance.leg.DateRules(
                TARGET2,
                com.openbank.libs.domain.calendar.BusinessDayConvention.UNADJUSTED,
            ),
        )
        assertThat(ScheduleGenerator.generate(leg).periods[0].fixingDate).isEqualTo(d("2026-04-02"))
    }

    @Test
    fun `observed then projected then missing - never silently zero`() {
        val schedule = ScheduleGenerator.generate(floatingLeg())
        val fixings = FixingSource.of(mapOf((index to d("2026-04-01")) to bd("0.02")))

        val projected = CashFlowProjector.project(schedule, fixings, flat3)
        val flows = projected.requireComplete()
        // 1,000,000 x (2.0 % + 0.1 %) x 91/360 = 5,308.33.
        assertThat(flows[0].amount).isEqualByComparingTo("5308.33")
        assertThat(flows[0].rateSource).isEqualTo(RateSource.OBSERVED)
        assertThat(flows[0].rate!!.indexValue).isEqualByComparingTo("0.02")
        assertThat(flows[0].fixingDate).isEqualTo(d("2026-04-01"))
        // 1,000,000 x (3.0 % + 0.1 %) x 92/360 = 7,922.22.
        assertThat(flows[1].amount).isEqualByComparingTo("7922.22")
        assertThat(flows[1].rateSource).isEqualTo(RateSource.PROJECTED)
        assertThat(projected.fixingsUsed).containsExactly(UsedFixing(index, d("2026-04-01"), bd("0.02")))

        val incomplete = CashFlowProjector.project(schedule, fixings)
        assertThat(incomplete).isInstanceOf(CashFlowProjection.Incomplete::class.java)
        assertThat((incomplete as CashFlowProjection.Incomplete).missing)
            .containsExactly(MissingFixing(1, index, d("2026-07-03")))
        assertThat(incomplete.flows).hasSize(1)
        assertThatThrownBy { incomplete.requireComplete() }.hasMessageContaining("missing fixings")
    }

    @Test
    fun `rate until first fixing is contractual and beats both the source and the curve`() {
        val schedule = ScheduleGenerator.generate(floatingLeg(rateUntilFirstFixing = bd("0.025")))
        val fixings = FixingSource.of(mapOf((index to d("2026-04-01")) to bd("0.09")))
        val flows = CashFlowProjector.project(schedule, fixings, flat3).requireComplete()
        assertThat(flows[0].rateSource).isEqualTo(RateSource.RATE_UNTIL_FIRST_FIXING)
        // 1,000,000 x 2.5 % x 91/360 = 6,319.44.
        assertThat(flows[0].amount).isEqualByComparingTo("6319.44")
        assertThat(flows[1].rateSource).isEqualTo(RateSource.PROJECTED)
    }

    @Test
    fun `cap and floor on index versus index plus spread`() {
        val floor0Index = RateBounds(floor = BigDecimal.ZERO, appliesTo = CapFloorAppliesTo.INDEX)
        val floor0AllIn = RateBounds(floor = BigDecimal.ZERO, appliesTo = CapFloorAppliesTo.INDEX_PLUS_SPREAD)
        // index -0.5 %, spread +1 %: floored index 0 + 1 % = 1 %; floored all-in max(0.5 %, 0) = 0.5 %.
        assertThat(floor0Index.apply(bd("-0.005"), bd("0.01"))).isEqualByComparingTo("0.01")
        assertThat(floor0AllIn.apply(bd("-0.005"), bd("0.01"))).isEqualByComparingTo("0.005")
        val cap4Index = RateBounds(cap = bd("0.04"), appliesTo = CapFloorAppliesTo.INDEX)
        val cap4AllIn = RateBounds(cap = bd("0.04"))
        // index 5 %, spread 1 %: capped index 4 % + 1 % = 5 %; capped all-in 4 %.
        assertThat(cap4Index.apply(bd("0.05"), bd("0.01"))).isEqualByComparingTo("0.05")
        assertThat(cap4AllIn.apply(bd("0.05"), bd("0.01"))).isEqualByComparingTo("0.04")
        assertThat(RateBounds.NONE.apply(bd("0.05"), bd("0.01"))).isEqualByComparingTo("0.06")
        assertThatThrownBy {
            RateBounds(cap = bd("0.01"), floor = bd("0.02"))
        }.isInstanceOf(IllegalArgumentException::class.java)

        val capped = ScheduleGenerator.generate(floatingLeg(bounds = cap4AllIn))
        val flows = CashFlowProjector.project(
            capped,
            FixingSource.NONE,
            ForwardCurve { _, _, _ ->
                bd("0.06")
            },
        ).requireComplete()
        assertThat(flows.map { it.rate!!.rate }).allSatisfy { assertThat(it).isEqualByComparingTo("0.04") }
    }

    @Test
    fun `reset frequency other than accrual frequency is rejected, not approximated`() {
        val leg = floatingLeg().let {
            it.copy(rate = (it.rate as RateSpec.Floating).copy(resetFrequency = Frequency.MONTHLY))
        }
        assertThatThrownBy { ScheduleGenerator.generate(leg) }.hasMessageContaining("reset frequency")
        assertThatThrownBy {
            RateSpec.Floating(" ", fixing = FixingConvention(2, TARGET2), resetFrequency = Frequency.QUARTERLY)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FixingConvention(-1, TARGET2) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `linear amortisation repays exactly with the residual on the last flow`() {
        val leg = fixedLeg(
            "2026-01-15",
            "2026-10-15",
            notional = NotionalSpec(bd("1000000.01"), AmortisationSpec.Linear),
        )
        val schedule = ScheduleGenerator.generate(leg)
        assertThat(schedule.periods.map { it.principal.toPlainString() })
            .containsExactly("333333.33", "333333.33", "333333.35")
        assertThat(schedule.periods.map { it.notional.toPlainString() })
            .containsExactly("1000000.01", "666666.68", "333333.35")
        val principal = CashFlowProjector.project(schedule).requireComplete().filter {
            it.kind == CashFlowKind.PRINCIPAL
        }
        assertThat(principal.fold(BigDecimal.ZERO) { a, f -> a + f.amount }).isEqualByComparingTo("1000000.01")
    }

    @Test
    fun `annuity has a level payment and repays exactly`() {
        val leg = fixedLeg(
            "2026-01-15",
            "2027-01-15",
            rate = RateSpec.Fixed(bd("0.04")),
            notional = NotionalSpec(bd("1000000"), AmortisationSpec.Annuity),
            dayCount = DayCount.THIRTY_360_BOND_BASIS,
        )
        val flows = CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete()
        val principal = flows.filter { it.kind == CashFlowKind.PRINCIPAL }
        assertThat(principal.fold(BigDecimal.ZERO) { a, f -> a + f.amount }).isEqualByComparingTo("1000000")
        val payments = flows.groupBy {
            it.periodIndex
        }.values.map { p -> p.fold(BigDecimal.ZERO) { a, f -> a + f.amount } }
        // Level to the cent except the last, which absorbs every rounding residual.
        assertThat(payments.dropLast(1).distinct()).hasSize(1)
        assertThat((payments.last() - payments.first()).abs()).isLessThanOrEqualTo(bd("0.05"))
        // The installment is the contract's: N·i/(1−(1+i)^−n), i = 4%/4 — 256,281.09 for 4 quarters.
        assertThat(payments.first()).isEqualByComparingTo("256281.09")
        assertThatThrownBy {
            ScheduleGenerator.generate(
                leg.copy(
                    rate = RateSpec.Floating(
                        index,
                        fixing = FixingConvention(2, TARGET2),
                        resetFrequency = Frequency.QUARTERLY,
                    ),
                ),
            )
        }.hasMessageContaining("annuity needs a fixed rate")
    }

    @Test
    fun `an annuity installment is the nominal-rate formula whatever the day count or holidays`() {
        // ACT/360 periods of 90..92 days would change a day-count-derived level payment; the
        // contractual installment does not move — only the interest/principal split does.
        val leg = fixedLeg(
            "2026-01-15",
            "2027-01-15",
            rate = RateSpec.Fixed(bd("0.04")),
            notional = NotionalSpec(bd("1000000"), AmortisationSpec.Annuity),
        )
        val flows = CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete()
        val first = flows.filter { it.periodIndex == 0 }.fold(BigDecimal.ZERO) { a, f -> a + f.amount }
        assertThat(first).isEqualByComparingTo("256281.09")
    }

    @Test
    fun `custom amortisation must match periods and sum to the notional`() {
        val ok =
            fixedLeg(
                "2026-01-15",
                "2026-07-15",
                notional = NotionalSpec(bd("100"), AmortisationSpec.Custom(listOf(bd("40"), bd("60")))),
            )
        assertThat(ScheduleGenerator.generate(ok).periods.map { it.principal }).containsExactly(bd("40"), bd("60"))
        val wrongSum = ok.copy(notional = NotionalSpec(bd("100"), AmortisationSpec.Custom(listOf(bd("40"), bd("50")))))
        assertThatThrownBy { ScheduleGenerator.generate(wrongSum) }.hasMessageContaining("sum to the notional")
        val wrongCount = ok.copy(notional = NotionalSpec(bd("100"), AmortisationSpec.Custom(listOf(bd("100")))))
        assertThatThrownBy { ScheduleGenerator.generate(wrongCount) }.hasMessageContaining("repayments for 2 periods")
        val negative = ok.copy(
            notional = NotionalSpec(bd("100"), AmortisationSpec.Custom(listOf(bd("-10"), bd("110")))),
        )
        assertThatThrownBy { ScheduleGenerator.generate(negative) }.hasMessageContaining("non-negative")
    }

    @Test
    fun `notional spec validation`() {
        assertThatThrownBy { NotionalSpec(BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NotionalSpec(bd("1"), AmortisationSpec.Linear, finalExchangeAmount = bd("2")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `signs - pay leg negative, initial exchange opposite to the leg`() {
        val leg = fixedLeg(
            "2026-01-15",
            "2026-04-15",
            Frequency.Zero,
            notional = NotionalSpec(bd("1000"), initialExchange = true),
        )
            .copy(payReceive = PayReceive.PAY)
        val flows = CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete()
        assertThat(
            flows.map {
                it.kind
            },
        ).containsExactly(CashFlowKind.NOTIONAL_EXCHANGE, CashFlowKind.INTEREST, CashFlowKind.PRINCIPAL)
        assertThat(flows[0].amount).isEqualByComparingTo("1000.00")
        assertThat(flows[0].periodIndex).isEqualTo(-1)
        assertThat(flows[0].accrualStart).isNull()
        assertThat(flows[1].amount.signum()).isEqualTo(-1)
        assertThat(flows[1].accrualStart).isEqualTo(d("2026-01-15"))
        assertThat(flows[1].accrualEnd).isEqualTo(d("2026-04-15"))
        assertThat(flows[2].amount).isEqualByComparingTo("-1000.00")
    }
}
