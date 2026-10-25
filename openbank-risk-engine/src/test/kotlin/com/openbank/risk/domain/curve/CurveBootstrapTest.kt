// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.pricing.DiscountCurve
import com.openbank.libs.finance.pricing.FlatZeroCurve
import com.openbank.libs.finance.pricing.LegPricer
import com.openbank.libs.finance.product.DealTerms
import com.openbank.libs.finance.product.FixedLegTerms
import com.openbank.libs.finance.product.FloatingLegTerms
import com.openbank.libs.finance.product.LegTemplates
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

class CurveBootstrapTest {
    private val asOf = LocalDate.parse("2026-09-30")
    private val mc = MathContext.DECIMAL128
    private val notional = BigDecimal("1000000000")
    private val par = BigDecimal("1e-8")

    private fun d(t: String, r: String) = MoneyMarketQuote(Tenor.parse(t), BigDecimal(r))

    private val quotes: List<CurveQuote> = listOf(
        d("ON", "0.0350"),
        d("1M", "0.0355"),
        d("3M", "0.0360"),
        d("6M", "0.0365"),
        FraQuote.parse("6x12", BigDecimal("0.0368")),
        FraQuote.parse("12x18", BigDecimal("0.0366")),
        SwapQuote.parse("2Y", BigDecimal("0.0362")),
        SwapQuote.parse("3Y", BigDecimal("0.0358")),
        SwapQuote.parse("5Y", BigDecimal("0.0360")),
        SwapQuote.parse("10Y", BigDecimal("0.0375")),
    )

    /** PV per unit notional of a deal built INDEPENDENTLY with LegTemplates (default cent rounding). */
    private fun perUnit(legs: List<com.openbank.libs.finance.leg.LegSpec>, curve: DiscountCurve): BigDecimal =
        legs.fold(BigDecimal.ZERO) { pv, leg ->
            val flows = CashFlowProjector.project(
                ScheduleGenerator.generate(leg),
                forward = curve.impliedForwards(),
            ).requireComplete()
            pv.add(LegPricer.presentValue(flows, curve, curve.valuationDate.minusDays(1)), mc)
        }.divide(notional, mc)

    private fun terms(start: LocalDate, end: LocalDate, c: CurveConventions) =
        DealTerms(CurrencyCode.of("CZK"), notional, start, end, c.calendar)

    @Test
    fun `every deposit, FRA and par swap reprices to par within 1e-8 off the bootstrapped curve`() {
        val c = CurveConventions.forCurrency("CZK")
        val curve = CurveBootstrap.bootstrap(CurveIndex.PRIBOR_3M, asOf, quotes)
        assertThat(curve.pillars).hasSize(quotes.size)
        quotes.forEach { q ->
            val legs = when (q) {
                is MoneyMarketQuote ->
                    listOf(LegTemplates.moneyMarket(PayReceive.RECEIVE, terms(asOf, q.tenor.endDate(asOf), c), q.rate))
                is FraQuote -> listOf(
                    LegTemplates.moneyMarket(
                        PayReceive.RECEIVE,
                        terms(asOf.plusMonths(q.startMonths.toLong()), asOf.plusMonths(q.endMonths.toLong()), c),
                        q.rate,
                    ),
                )
                is SwapQuote -> LegTemplates.interestRateSwap(
                    PayReceive.PAY,
                    terms(asOf, asOf.plusYears(q.years.toLong()), c),
                    FixedLegTerms(q.rate, Frequency.ANNUAL, DayCount.ACT_360),
                    FloatingLegTerms("PRIBOR_3M", Frequency.QUARTERLY, DayCount.ACT_360, fixingLagBusinessDays = 0),
                )
            }
            assertThat(perUnit(legs, curve).abs()).describedAs("${q.instrument} ${q.code}").isLessThan(par)
        }
        // The negative case: the same check on a curve built from quotes 1bp off must FAIL par.
        val off = CurveBootstrap.bootstrap(
            CurveIndex.PRIBOR_3M,
            asOf,
            quotes.map { CurveQuote.of(it.instrument, it.code, it.rate.add(BigDecimal("0.0001"))) },
        )
        val fiveYear = LegTemplates.interestRateSwap(
            PayReceive.PAY,
            terms(asOf, asOf.plusYears(5), c),
            FixedLegTerms(BigDecimal("0.0360"), Frequency.ANNUAL, DayCount.ACT_360),
            FloatingLegTerms("PRIBOR_3M", Frequency.QUARTERLY, DayCount.ACT_360, fixingLagBusinessDays = 0),
        )
        assertThat(perUnit(fiveYear, off).abs()).isGreaterThan(BigDecimal("1e-5"))
    }

    @Test
    fun `a semi-annual fixed-leg convention also reprices its swaps to par`() {
        val semi = CurveConventions.forCurrency("CZK").copy(fixedFrequency = Frequency.SEMI_ANNUAL)
        val curve = CurveBootstrap.bootstrap(CurveIndex.PRIBOR_6M, asOf, quotes, semi)
        val swap = LegTemplates.interestRateSwap(
            PayReceive.RECEIVE,
            terms(asOf, asOf.plusYears(5), semi),
            FixedLegTerms(BigDecimal("0.0360"), Frequency.SEMI_ANNUAL, DayCount.ACT_360),
            FloatingLegTerms("PRIBOR_6M", Frequency.SEMI_ANNUAL, DayCount.ACT_360, fixingLagBusinessDays = 0),
        )
        assertThat(perUnit(swap, curve).abs()).isLessThan(par)
    }

    @Test
    fun `EUR swaps use the 30-360 annual fixed leg and still reprice to par`() {
        val eur = CurveConventions.forCurrency("EUR")
        assertThat(eur.fixedDayCount).isEqualTo(DayCount.THIRTY_360_BOND_BASIS)
        val curve = CurveBootstrap.bootstrap(
            CurveIndex.EURIBOR_3M,
            asOf,
            listOf(d("3M", "0.02"), SwapQuote(2, BigDecimal("0.021"))),
        )
        val swap = LegTemplates.interestRateSwap(
            PayReceive.PAY,
            DealTerms(CurrencyCode.of("EUR"), notional, asOf, asOf.plusYears(2), eur.calendar),
            FixedLegTerms(BigDecimal("0.021"), Frequency.ANNUAL, DayCount.THIRTY_360_BOND_BASIS),
            FloatingLegTerms("EURIBOR_3M", Frequency.QUARTERLY, DayCount.ACT_360, fixingLagBusinessDays = 0),
        )
        assertThat(perUnit(swap, curve).abs()).isLessThan(par)
    }

    @Test
    fun `discount factors are positive and strictly decreasing, or the bootstrap fails typed`() {
        val curve = CurveBootstrap.bootstrap(CurveIndex.PRIBOR_3M, asOf, quotes)
        val dfs = curve.pillars.map { curve.discountFactor(it.date) }
        assertThat(dfs.zipWithNext().all { (a, b) -> b < a }).isTrue()
        assertThat(dfs.all { it.signum() > 0 && it < BigDecimal.ONE }).isTrue()

        assertThatThrownBy {
            CurveBootstrap.bootstrap(CurveIndex.CZEONIA, asOf, listOf(d("1M", "0.03"), d("3M", "-0.05")))
        }.isInstanceOfSatisfying(CurveBootstrapException::class.java) {
            assertThat(it.reason).isEqualTo(CurveBootstrapException.Reason.NOT_ARBITRAGE_FREE)
        }
    }

    @Test
    fun `a weekend or holiday as-of still bootstraps - the overnight deposit runs to the next business day`() {
        listOf("2026-05-31", "2026-09-28", "2026-10-31").map(LocalDate::parse).forEach { day ->
            val curve = CurveBootstrap.bootstrap(
                CurveIndex.CZEONIA,
                day,
                listOf(d("ON", "0.035"), d("3M", "0.036"), d("1Y", "0.038")),
            )
            assertThat(curve.pillars).hasSize(3)
            assertThat(ReferenceCurveSet.build(day, java.time.Instant.EPOCH).curves).hasSize(CurveIndex.entries.size)
        }
    }

    @Test
    fun `malformed quotes are refused with a typed reason`() {
        assertThatThrownBy { Tenor.parse("2Y") }.hasMessageContaining("exceeds one year")
        assertThatThrownBy { Tenor.parse("3X") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            FraQuote.parse("6x3", BigDecimal("0.01"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SwapQuote.parse("2M", BigDecimal("0.01"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CurveBootstrap.bootstrap(CurveIndex.ESTR, asOf, listOf(d("1M", "3.5"))) }
            .isInstanceOfSatisfying(CurveBootstrapException::class.java) {
                assertThat(it.reason).isEqualTo(CurveBootstrapException.Reason.NOT_A_FRACTION)
            }
        assertThatThrownBy {
            CurveBootstrap.bootstrap(CurveIndex.ESTR, asOf, listOf(d("12M", "0.01"), d("1Y", "0.02")))
        }
            .isInstanceOfSatisfying(CurveBootstrapException::class.java) {
                assertThat(it.reason).isEqualTo(CurveBootstrapException.Reason.DUPLICATE_PILLAR)
            }
        assertThatThrownBy { CurveBootstrap.bootstrap(CurveIndex.ESTR, asOf, emptyList()) }
            .isInstanceOfSatisfying(CurveBootstrapException::class.java) {
                assertThat(it.reason).isEqualTo(CurveBootstrapException.Reason.NO_QUOTES)
            }
        assertThat(CurveQuote.of(CurveInstrument.FRA, "3x6", BigDecimal("0.01")).code).isEqualTo("3x6")
        assertThat(CurveQuote.of(CurveInstrument.SWAP, "5y", BigDecimal("0.01")).code).isEqualTo("5Y")
    }

    @Test
    fun `the reference set bootstraps every index from its deposits, FRAs and swaps`() {
        val set = ReferenceCurveSet.build(asOf, java.time.Instant.EPOCH)
        assertThat(set.curves).hasSize(CurveIndex.entries.size)
        set.curves.forEach { (index, curve) ->
            assertThat(curve.pillars).hasSize(ReferenceCurveSet.QUOTES.getValue(index).size)
            assertThat(curve.pillars.last().date).isAfter(asOf.plusYears(9))
        }
    }
}

/** The risk engine's [Curve] IS a libs-finance [DiscountCurve]; held to the library's own formulas. */
class CurveAdapterTest {
    private val asOf = LocalDate.parse("2026-09-30")
    private val mc = MathContext.DECIMAL128

    @Test
    fun `a flat Curve discounts exactly like the library's FlatZeroCurve`() {
        val r = BigDecimal("0.0425")
        val curve: DiscountCurve = Curve(CurveIndex.CZEONIA, asOf, listOf(CurvePillar(asOf.plusYears(1), r)))
        val reference = FlatZeroCurve(asOf, r)
        assertThat(curve.valuationDate).isEqualTo(asOf)
        listOf(asOf, asOf.plusDays(1), asOf.plusMonths(7), asOf.plusYears(3)).forEach { date ->
            assertThat(
                curve.discountFactor(date).subtract(reference.discountFactor(date)).abs(),
            ).isLessThan(BigDecimal("1e-28"))
        }
    }

    @Test
    fun `zero rate and implied forwards agree with the DiscountCurve definitions`() {
        val curve = CurveBootstrap.bootstrap(
            CurveIndex.PRIBOR_3M,
            asOf,
            ReferenceCurveSet.QUOTES.getValue(CurveIndex.PRIBOR_3M),
        )
        val asLibrary = object : DiscountCurve {
            override val valuationDate = asOf

            override fun discountFactor(date: LocalDate) = curve.discountFactor(date)
        }
        val start = asOf.plusMonths(4)
        val end = asOf.plusMonths(7)
        listOf(asOf.plusMonths(2), asOf.plusYears(4)).forEach { date ->
            assertThat(curve.zeroRate(date).subtract(asLibrary.zeroRate(date)).abs()).isLessThan(BigDecimal("1e-20"))
        }
        val viaCurve = curve.projection().forwardRate(start, end, DayCount.ACT_365_FIXED)
        assertThat(viaCurve.subtract(curve.forwardRate(start, end)).abs()).isLessThan(BigDecimal("1e-25"))
        assertThat(viaCurve.subtract(asLibrary.impliedForwards().forwardRate(start, end, DayCount.ACT_365_FIXED)).abs())
            .isLessThan(BigDecimal("1e-25"))
        assertThat(Curve.yearFraction(asOf, asOf.plusDays(73))).isEqualByComparingTo(BigDecimal("0.2"))
        assertThat(mc.precision).isEqualTo(34)
    }
}
