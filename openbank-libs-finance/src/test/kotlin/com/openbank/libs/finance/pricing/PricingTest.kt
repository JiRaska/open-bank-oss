// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.pricing

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.EUR
import com.openbank.libs.finance.TARGET2
import com.openbank.libs.finance.bd
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.libs.finance.cashflow.ProjectedCashFlow
import com.openbank.libs.finance.d
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.product.DealTerms
import com.openbank.libs.finance.product.FixedLegTerms
import com.openbank.libs.finance.product.FloatingLegTerms
import com.openbank.libs.finance.product.LegTemplates
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Period

class PricingTest {
    private val asOf = d("2026-01-15")
    private val curve = FlatZeroCurve(asOf, bd("0.03"))

    private fun flow(date: String, amount: String, currency: com.openbank.libs.domain.money.CurrencyCode = EUR) =
        ProjectedCashFlow(0, CashFlowKind.PRINCIPAL, null, d(date), currency, bd(amount), null, bd(amount))

    @Test
    fun `exp and ln are exact to working precision`() {
        assertThat(BigMath.exp(BigDecimal.ONE).setScale(30, RoundingMode.HALF_UP))
            .isEqualByComparingTo("2.718281828459045235360287471353")
        assertThat(
            BigMath.exp(bd("-12.5")).setScale(20, RoundingMode.HALF_UP),
        ).isEqualByComparingTo("0.00000372665317207867")
        assertThat(
            BigMath.ln(BigMath.exp(bd("0.0375"))).setScale(28, RoundingMode.HALF_UP),
        ).isEqualByComparingTo("0.0375")
        assertThatThrownBy { BigMath.ln(BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `discounting a known flow`() {
        // 1,000 in 365 days at a flat 3 % continuous zero (ACT/365F): 1000 x e^-0.03 = 970.4455335485...
        val pv = LegPricer.presentValue(listOf(flow("2027-01-15", "1000")), curve)
        assertThat(pv.setScale(10, RoundingMode.HALF_UP)).isEqualByComparingTo("970.4455335485")
        assertThat(curve.discountFactor(asOf)).isEqualByComparingTo("1")
        assertThat(curve.zeroRate(d("2027-01-15")).setScale(20, RoundingMode.HALF_UP)).isEqualByComparingTo("0.03")
        assertThatThrownBy { curve.zeroRate(asOf) }.isInstanceOf(IllegalArgumentException::class.java)
        // A flow paid on or before the valuation date is settled and carries no value.
        assertThat(
            LegPricer.presentValue(listOf(flow("2026-01-15", "1000"), flow("2025-12-31", "5")), curve),
        ).isEqualByComparingTo("0")
        assertThatThrownBy {
            LegPricer.presentValue(
                listOf(flow("2027-01-15", "1"), flow("2027-01-15", "1", com.openbank.libs.finance.CZK)),
                curve,
            )
        }
            .hasMessageContaining("currencies")
    }

    @Test
    fun `implied forwards reproduce the curve`() {
        val fwd = curve.impliedForwards().forwardRate(d("2026-04-15"), d("2026-07-15"), DayCount.ACT_360)
        // (e^(0.03 x 91/365) - 1) / (91/360)
        val expected = BigMath.exp(bd("0.03").multiply(bd("91")).divide(bd("365"), PRICING_MC))
            .subtract(BigDecimal.ONE).divide(bd("91").divide(bd("360"), PRICING_MC), PRICING_MC)
        assertThat(fwd.subtract(expected).abs().toDouble()).isLessThan(1e-30)
        assertThatThrownBy {
            curve.impliedForwards().forwardRate(asOf, asOf, DayCount.ACT_360)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun swap(side: PayReceive): Valuation {
        val terms = DealTerms(EUR, bd("10000000"), d("2026-01-19"), d("2031-01-19"), TARGET2)
        val legs = LegTemplates.interestRateSwap(
            side,
            terms,
            FixedLegTerms(bd("0.03"), Frequency.ANNUAL, DayCount.THIRTY_E_360),
            FloatingLegTerms("EURIBOR-6M", Frequency.SEMI_ANNUAL, DayCount.ACT_360),
        )
        return LegPricer.valuation(legs.map { LegPricer.valuation(ScheduleGenerator.generate(it), FixingSource.NONE) })
    }

    @Test
    fun `PV01 sign and magnitude for payer versus receiver swap`() {
        val payer = Sensitivities.pv01(swap(PayReceive.PAY), curve)
        val receiver = Sensitivities.pv01(swap(PayReceive.RECEIVE), curve)
        // 10m x ~4.6 years of annuity x 1bp ~ 4,600; payer gains when rates rise.
        assertThat(payer.toDouble()).isBetween(4_000.0, 5_000.0)
        assertThat(receiver.toDouble()).isBetween(-5_000.0, -4_000.0)
        assertThat(payer.add(receiver).abs().toDouble()).isLessThan(1e-6)
    }

    @Test
    fun `bucketed DV01 partitions the parallel shift`() {
        val swap = swap(PayReceive.PAY)
        val tenors =
            listOf(Period.ofYears(1), Period.ofYears(2), Period.ofYears(3), Period.ofYears(5), Period.ofYears(10))
        val buckets = Sensitivities.bucketedDv01(swap, curve, tenors)
        assertThat(buckets.map { it.tenor }).isEqualTo(tenors)
        val sum = buckets.fold(BigDecimal.ZERO) { a, b -> a + b.dv01 }
        val parallel = Sensitivities.pv01(swap, curve)
        assertThat(sum.toDouble()).isCloseTo(parallel.toDouble(), within(parallel.abs().toDouble() * 1e-3))
        // The 10y bucket only sees the last few days past the 5y pillar: well under 1 % of the risk.
        assertThat(buckets.last().dv01.abs().toDouble()).isLessThan(parallel.abs().toDouble() * 1e-2)
        assertThat(buckets.first().dv01.signum()).isEqualTo(1)
        assertThatThrownBy {
            Sensitivities.bucketedDv01(swap, curve, emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Sensitivities.bucketedDv01(swap, curve, listOf(Period.ofYears(2), Period.ofYears(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `hat weights sum to one everywhere`() {
        val pillars = listOf(bd("1"), bd("2"), bd("5"))
        listOf("0", "0.5", "1", "1.5", "2", "3.5", "5", "7").map(::bd).forEach { t ->
            val total = pillars.indices.fold(BigDecimal.ZERO) { a, i -> a + Sensitivities.hat(pillars, i, t) }
            assertThat(total).isEqualByComparingTo("1")
        }
        assertThat(Sensitivities.hat(pillars, 1, bd("3.5"))).isEqualByComparingTo("0.5")
    }

    @Test
    fun `FX forward by covered interest parity`() {
        // EUR/CZK spot 25, EUR 2 %, CZK 4 % flat continuous, 1y: F = 25 e^0.02 = 25.505033500668895...
        val eur = FlatZeroCurve(asOf, bd("0.02"))
        val czk = FlatZeroCurve(asOf, bd("0.04"))
        val q = FxForwardPricer.forward(bd("25"), asOf, d("2027-01-15"), eur, czk)
        assertThat(q.forwardRate.setScale(12, RoundingMode.HALF_UP)).isEqualByComparingTo("25.505033500669")
        assertThat(q.forwardPoints.setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo("5050.335007")
        // Buying 1m EUR at 25.40 forward: (F - K) x 1m discounted in CZK.
        val pv = FxForwardPricer.presentValue(bd("1000000"), bd("25.40"), q, d("2027-01-15"), czk)
        val expected = bd("1000000").multiply(q.forwardRate - bd("25.40")).multiply(czk.discountFactor(d("2027-01-15")))
        assertThat(pv.subtract(expected).abs().toDouble()).isLessThan(1e-20)
    }

    @Test
    fun `FRA settlement is discounted at the fixing`() {
        // 1m x (4 % - 3 %) x 0.25 / (1 + 4 % x 0.25) = 2,475.2475247524...
        val buyer = FraPricer.settlementAmount(
            bd("1000000"),
            bd("0.03"),
            bd("0.04"),
            YearFraction.of(90, 360),
            PayReceive.PAY,
        )
        assertThat(buyer.setScale(10, RoundingMode.HALF_UP)).isEqualByComparingTo("2475.2475247525")
        val seller = FraPricer.settlementAmount(
            bd("1000000"),
            bd("0.03"),
            bd("0.04"),
            YearFraction.of(90, 360),
            PayReceive.RECEIVE,
        )
        assertThat(seller).isEqualByComparingTo(buyer.negate())
    }

    @Test
    fun `shifted curve bumps zero rates`() {
        val shifted = ShiftedCurve(curve, bd("0.01"))
        assertThat(shifted.zeroRate(d("2027-01-15")).setScale(20, RoundingMode.HALF_UP)).isEqualByComparingTo("0.04")
        assertThat(shifted.valuationDate).isEqualTo(asOf)
    }
}
