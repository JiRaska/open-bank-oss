// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.product

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.CERTIS
import com.openbank.libs.finance.CZK
import com.openbank.libs.finance.EUR
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.bd
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.cashflow.ProjectedCashFlow
import com.openbank.libs.finance.d
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegTemplatesTest {
    private fun flows(leg: LegSpec): List<ProjectedCashFlow> =
        CashFlowProjector.project(ScheduleGenerator.generate(leg)).requireComplete()

    private fun summary(leg: LegSpec) = flows(leg).map { "${it.kind}@${it.paymentDate}=${it.amount.toPlainString()}" }

    @Test
    fun `money-market deposit over the year end on CERTIS`() {
        val leg = LegTemplates.moneyMarket(
            PayReceive.RECEIVE,
            DealTerms(CZK, bd("10000000"), d("2026-12-23"), d("2027-03-23"), CERTIS),
            bd("0.05"),
        )
        // 90 days ACT/360: 10,000,000 x 5 % x 90/360 = 125,000.00.
        assertThat(summary(leg)).containsExactly(
            "NOTIONAL_EXCHANGE@2026-12-23=-10000000.00",
            "INTEREST@2027-03-23=125000.00",
            "PRINCIPAL@2027-03-23=10000000.00",
        )
    }

    @Test
    fun `treasury bill bought at a discount, redeemed at face`() {
        val leg = LegTemplates.treasuryBill(
            DealTerms(CZK, bd("1000000"), d("2026-01-15"), d("2026-04-16"), CERTIS),
            bd("0.04"),
        )
        // price = 1,000,000 / (1 + 4 % x 91/360) = 989,990.10
        assertThat(summary(leg)).containsExactly(
            "NOTIONAL_EXCHANGE@2026-01-15=-989990.10",
            "NOTIONAL_EXCHANGE@2026-04-16=1000000.00",
        )
    }

    @Test
    fun `fixed coupon bond accrues unadjusted, pays rolled`() {
        val leg = LegTemplates.fixedCouponBond(
            DealTerms(EUR, bd("1000000"), d("2026-03-15"), d("2031-03-15"), TARGET2),
            FixedLegTerms(bd("0.03"), Frequency.ANNUAL, DayCount.THIRTY_E_360),
        )
        assertThat(summary(leg)).containsExactly(
            "INTEREST@2027-03-15=30000.00",
            "INTEREST@2028-03-15=30000.00",
            "INTEREST@2029-03-15=30000.00",
            "INTEREST@2030-03-15=30000.00",
            // Sat 15 Mar 2031 pays Mon 17 Mar, still accruing exactly one 30E/360 year.
            "INTEREST@2031-03-17=30000.00",
            "PRINCIPAL@2031-03-17=1000000.00",
        )
        assertThat(ScheduleGenerator.generate(leg).periods.last().accrualEnd).isEqualTo(d("2031-03-15"))
    }

    @Test
    fun `FRA fixes T-2 and settles discounted`() {
        val fra = LegTemplates.fra(
            PayReceive.PAY,
            DealTerms(EUR, bd("1000000"), d("2026-04-15"), d("2026-07-15"), TARGET2),
            bd("0.03"),
            "EURIBOR-3M",
        )
        assertThat(fra.fixingDate).isEqualTo(d("2026-04-13"))
        assertThat(fra.yearFraction).isEqualTo(YearFraction.of(91, 360))
        assertThat(fra.settlementAmount(bd("0.03")).signum()).isZero()
        assertThat(fra.settlementAmount(bd("0.04")).signum()).isEqualTo(1)
    }

    @Test
    fun `interest rate swap has mirrored legs without notional exchange`() {
        val legs = LegTemplates.interestRateSwap(
            PayReceive.PAY,
            DealTerms(EUR, bd("5000000"), d("2026-01-19"), d("2028-01-19"), TARGET2),
            FixedLegTerms(bd("0.025"), Frequency.ANNUAL, DayCount.THIRTY_E_360),
            FloatingLegTerms(
                "EURIBOR-6M",
                Frequency.SEMI_ANNUAL,
                DayCount.ACT_360,
                fixingCalendar = Calendars.joint(com.openbank.libs.domain.calendar.BusinessCalendar.target2()),
            ),
        )
        assertThat(legs.map { it.payReceive }).containsExactly(PayReceive.PAY, PayReceive.RECEIVE)
        assertThat(legs[1].rate).isInstanceOf(RateSpec.Floating::class.java)
        assertThat(flows(legs[0]).map { it.kind }).containsOnly(CashFlowKind.INTEREST)
        assertThat(flows(legs[0]).map { it.amount.toPlainString() }).containsExactly("-125000.00", "-125000.00")
    }

    @Test
    fun `FX forward and FX swap exchange the right amounts`() {
        val fwd = LegTemplates.fxForward(
            d("2026-01-15"),
            d("2026-04-17"),
            FxAmount(EUR, bd("1000000")),
            FxAmount(CZK, bd("25300000")),
            Calendars.forCurrencies("EUR", "CZK"),
        )
        assertThat(fwd.flatMap { summary(it) })
            .containsExactly("NOTIONAL_EXCHANGE@2026-04-17=1000000.00", "NOTIONAL_EXCHANGE@2026-04-17=-25300000.00")

        val swap = LegTemplates.fxSwap(
            d("2026-01-19"),
            d("2026-07-20"),
            FxAmount(EUR, bd("1000000")),
            FxSwapQuote(CZK, bd("25.00"), bd("25.10"), bankBuysBaseNear = true),
            Calendars.forCurrencies("EUR", "CZK"),
        )
        assertThat(
            summary(swap[0]),
        ).containsExactly("NOTIONAL_EXCHANGE@2026-01-19=1000000.00", "NOTIONAL_EXCHANGE@2026-07-20=-1000000.00")
        assertThat(
            summary(swap[1]),
        ).containsExactly("NOTIONAL_EXCHANGE@2026-01-19=-25000000.00", "NOTIONAL_EXCHANGE@2026-07-20=25100000.00")
        val sell = LegTemplates.fxSwap(
            d("2026-01-19"),
            d("2026-07-20"),
            FxAmount(EUR, bd("1000000")),
            FxSwapQuote(CZK, bd("25.00"), bd("25.10"), bankBuysBaseNear = false),
            Calendars.forCurrencies("EUR", "CZK"),
        )
        assertThat(summary(sell[0]).first()).isEqualTo("NOTIONAL_EXCHANGE@2026-01-19=-1000000.00")
    }

    @Test
    fun `repo borrows cash against referenced collateral`() {
        val repo = LegTemplates.repo(
            PayReceive.PAY,
            DealTerms(CZK, bd("5000000"), d("2026-01-15"), d("2026-01-22"), CERTIS),
            bd("0.04"),
            CollateralReference("CZ0000000000", bd("5000000")),
        )
        assertThat(repo.collateral.isin).isEqualTo("CZ0000000000")
        // 5,000,000 x 4 % x 7/360 = 3,888.89 paid.
        assertThat(summary(repo.cashLeg)).containsExactly(
            "NOTIONAL_EXCHANGE@2026-01-15=5000000.00",
            "INTEREST@2026-01-22=-3888.89",
            "PRINCIPAL@2026-01-22=-5000000.00",
        )
    }
}
