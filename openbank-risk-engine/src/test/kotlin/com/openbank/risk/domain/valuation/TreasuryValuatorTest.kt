// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.pricing.FxForwardPricer
import com.openbank.libs.finance.product.DealTerms
import com.openbank.libs.finance.product.FixedLegTerms
import com.openbank.libs.finance.product.FloatingLegTerms
import com.openbank.libs.finance.product.FxAmount
import com.openbank.libs.finance.product.LegTemplates
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.risk.domain.curve.ReferenceCurveSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TreasuryValuatorTest {
    private val asOf = LocalDate.parse("2026-09-30")
    private val curves = ReferenceCurveSet.build(asOf, Instant.EPOCH).curves
    private val spot = BigDecimal("24.50")
    private val market = MarketState(asOf, curves, "CZK", mapOf("EUR" to spot))
    private val valuator = TreasuryValuator(market)
    private val czk = Calendars.forCurrencies("CZK")
    private val notional = BigDecimal("100000000")

    private fun irs(fixedSide: PayReceive, rate: String = "0.0345"): List<LegSpec> = LegTemplates.interestRateSwap(
        fixedSide,
        DealTerms(CurrencyCode.of("CZK"), notional, asOf, asOf.plusYears(5), czk),
        FixedLegTerms(BigDecimal(rate), Frequency.ANNUAL, DayCount.ACT_360),
        FloatingLegTerms("PRIBOR-3M", Frequency.QUARTERLY, DayCount.ACT_360),
    )

    private fun ready(legs: List<LegSpec>, stored: StoredFlowSet? = null, id: UUID = UUID.randomUUID()) = (
        valuator.prepare(
            ValuableDeal(id, "BOOK-1", DealKind.ACTUAL, legs, stored),
        ) as TreasuryValuator.Preparation.Ready
        ).prepared

    @Test
    fun `PV01 sign convention - a payer of fixed gains when rates rise, a receiver and a bond lose`() {
        val payer = valuator.sensitivities(listOf(ready(irs(PayReceive.PAY))), BucketSet.IRRBB_STANDARD)
        val receiver = valuator.sensitivities(listOf(ready(irs(PayReceive.RECEIVE))), BucketSet.IRRBB_STANDARD)
        val bond = LegTemplates.fixedCouponBond(
            DealTerms(CurrencyCode.of("CZK"), notional, asOf.minusMonths(3), asOf.plusYears(7), czk),
            FixedLegTerms(BigDecimal("0.04"), Frequency.ANNUAL, DayCount.ACT_ACT_ISDA),
        )
        val held = valuator.sensitivities(listOf(ready(listOf(bond))), BucketSet.IRRBB_STANDARD)
        assertThat(payer.pv01.signum()).isEqualTo(1)
        assertThat(receiver.pv01.signum()).isEqualTo(-1)
        assertThat(held.pv01.signum()).isEqualTo(-1)
        // Roughly notional × annuity × 1bp for a 5Y swap: tens of thousands of CZK per bp.
        assertThat(payer.pv01.abs()).isBetween(BigDecimal("30000"), BigDecimal("60000"))
    }

    @Test
    fun `key-rate DV01 buckets sum to PV01 to second order`() {
        val s = valuator.sensitivities(listOf(ready(irs(PayReceive.PAY))), BucketSet.IRRBB_STANDARD)
        val sum = s.dv01.fold(BigDecimal.ZERO) { a, b -> a.add(b.dv01) }
        assertThat(sum.subtract(s.pv01).abs()).isLessThan(s.pv01.abs().multiply(BigDecimal("0.001")))
        assertThat(s.dv01.map { it.bucket }).containsExactly("1D", "1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "10Y")
        assertThat(s.dv01.last().dv01.abs()).isLessThan(s.dv01.first { it.bucket == "5Y" }.dv01.abs())
    }

    @Test
    fun `an FX forward struck at the fair forward has zero PV, and its FX delta is 1 percent of the EUR leg`() {
        val maturity = asOf.plusMonths(6)
        val eurNotional = BigDecimal("1000000")
        val fair = FxForwardPricer.forward(
            spot,
            asOf,
            maturity,
            market.discount("EUR")!!,
            market.discount("CZK")!!,
        ).forwardRate
        val legs = LegTemplates.fxForward(
            asOf,
            maturity,
            FxAmount(CurrencyCode.of("EUR"), eurNotional),
            FxAmount(CurrencyCode.of("CZK"), eurNotional.multiply(fair).setScale(2, RoundingMode.HALF_UP)),
            Calendars.forCurrencies("CZK", "EUR"),
        )
        val prepared = ready(legs)
        val v = valuator.value(prepared)
        assertThat(v.dirty!!.abs()).isLessThan(BigDecimal("1.00")) // cent rounding of the CZK amount only
        val s = valuator.sensitivities(listOf(prepared), BucketSet.IRRBB_STANDARD)
        val eurLeg = v.legs.first { it.currency == "EUR" }
        assertThat(
            s.fxDelta.getValue("EUR"),
        ).isEqualByComparingTo(eurLeg.pvReporting.multiply(BigDecimal("0.01"), MathContext.DECIMAL128))
        assertThat(s.fxDelta).doesNotContainKey("CZK")

        // Negative case: struck 10 points off the fair rate, PV is clearly not zero.
        val off = LegTemplates.fxForward(
            asOf,
            maturity,
            FxAmount(CurrencyCode.of("EUR"), eurNotional),
            FxAmount(CurrencyCode.of("CZK"), eurNotional.multiply(fair.add(BigDecimal("0.10")))),
            Calendars.forCurrencies("CZK", "EUR"),
        )
        assertThat(valuator.value(ready(off)).dirty!!.abs()).isGreaterThan(BigDecimal("90000"))
    }

    @Test
    fun `accrued interest is linear within the running period and clean is dirty minus accrued`() {
        val deposit = LegTemplates.moneyMarket(
            PayReceive.RECEIVE,
            DealTerms(CurrencyCode.of("CZK"), notional, asOf.minusDays(30), asOf.plusDays(60), czk),
            BigDecimal("0.036"),
        )
        val v = valuator.value(ready(listOf(deposit)))
        val leg = v.legs.single()
        // 90 days of interest on ACT/360, 30 of them elapsed (dates are business days in the CZK calendar).
        val interest = notional.multiply(BigDecimal("0.036")).multiply(BigDecimal(90)).divide(BigDecimal(360))
        assertThat(
            leg.accrued.subtract(interest.divide(BigDecimal(3), MathContext.DECIMAL128)).abs(),
        ).isLessThan(BigDecimal("2000"))
        assertThat(v.clean).isEqualByComparingTo(v.dirty!!.subtract(v.accrued))
        assertThat(v.flowSource).isEqualTo(FlowSource.DERIVED)
    }

    @Test
    fun `a stored FACT replaces the derived flow, a cancelled one is dropped, and the deal says STORED`() {
        val legs = irs(PayReceive.PAY)
        val base = valuator.value(ready(legs))
        val dealId = UUID.randomUUID()
        val firstFloat = LegTemplates.interestRateSwap(
            PayReceive.PAY,
            DealTerms(CurrencyCode.of("CZK"), notional, asOf, asOf.plusYears(5), czk),
            FixedLegTerms(BigDecimal("0.0345"), Frequency.ANNUAL, DayCount.ACT_360),
            FloatingLegTerms("PRIBOR-3M", Frequency.QUARTERLY, DayCount.ACT_360),
        )[1]
        val fixed = StoredCashFlow(
            legIndex = 1,
            periodIndex = 0,
            kind = CashFlowKind.INTEREST,
            direction = FlowDirection.IN,
            currency = "CZK",
            paymentDate = asOf.plusMonths(3),
            accrualStart = asOf,
            accrualEnd = asOf.plusMonths(3),
            amount = BigDecimal("2000000.00"),
            rateSource = "OBSERVED",
            state = "FIXED",
        )
        val stored = valuator.value(ready(legs, StoredFlowSet(dealId, 2, "h", listOf(fixed)), dealId))
        assertThat(stored.flowSource).isEqualTo(FlowSource.STORED)
        assertThat(stored.dirty).isGreaterThan(base.dirty)
        val projected = fixed.copy(state = "PROJECTED", rateSource = "PROJECTED")
        val unchanged = valuator.value(ready(legs, StoredFlowSet(dealId, 3, "h", listOf(projected)), dealId))
        assertThat(unchanged.dirty).isEqualByComparingTo(base.dirty) // a projection is re-derived, not taken
        val cancelled = fixed.copy(state = "CANCELLED")
        val dropped = valuator.value(ready(legs, StoredFlowSet(dealId, 4, "h", listOf(cancelled)), dealId))
        assertThat(dropped.dirty).isLessThan(base.dirty)
        assertThat(firstFloat.payReceive).isEqualTo(PayReceive.RECEIVE)
    }

    @Test
    fun `a deal on a currency or index without a curve is unpriced, never priced on another curve`() {
        val usd = LegTemplates.moneyMarket(
            PayReceive.RECEIVE,
            DealTerms(CurrencyCode.of("USD"), notional, asOf, asOf.plusMonths(3), czk),
            BigDecimal("0.05"),
        )
        val p = valuator.prepare(ValuableDeal(UUID.randomUUID(), null, DealKind.ACTUAL, listOf(usd)))
        assertThat(p).isInstanceOf(TreasuryValuator.Preparation.Unpriced::class.java)
        val unknownIndex = irs(PayReceive.PAY).mapIndexed { i, l ->
            if (i ==
                1
            ) {
                l.copy(rate = (l.rate as com.openbank.libs.finance.leg.RateSpec.Floating).copy(index = "LIBOR-3M"))
            } else {
                l
            }
        }
        assertThat(valuator.prepare(ValuableDeal(UUID.randomUUID(), null, DealKind.ACTUAL, unknownIndex)))
            .isInstanceOf(TreasuryValuator.Preparation.Unpriced::class.java)
    }

    @Test
    fun `bucket sets resolve by id or explicit list`() {
        assertThat(BucketSet.resolve(null, null)).isEqualTo(BucketSet.IRRBB_STANDARD)
        assertThat(BucketSet.resolve(null, "1M, 1Y,5Y").tenors).hasSize(3)
        org.assertj.core.api.Assertions.assertThatThrownBy { BucketSet.resolve("nope", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy { BucketSet.resolve(null, "1Y,1M") }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy { BucketSet.resolve(null, "1Y,1Y") }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy { BucketSet.resolve(null, "1Y,51Y") }
            .hasMessageContaining("50Y")
        assertThat(BucketSet.resolve(null, "1Y,50Y").tenors).hasSize(2)
        assertThat(BucketSet.resolve(null, (1..30).joinToString(",") { "${it}M" }).tenors).hasSize(30)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BucketSet.resolve(null, (1..31).joinToString(",") { "${it}M" })
        }
            .hasMessageContaining("at most 30")
    }
}
