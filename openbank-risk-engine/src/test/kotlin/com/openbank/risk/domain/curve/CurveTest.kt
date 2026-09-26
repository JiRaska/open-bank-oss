// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class BigMathTest {

    @Test
    fun `exp and ln agree with the platform to double precision and invert each other to 1e-30`() {
        listOf("-3.2", "-0.05", "0.0001", "0.7", "2.5").forEach { s ->
            val x = BigDecimal(s)
            assertThat(BigMath.exp(x).toDouble()).isCloseTo(
                Math.exp(x.toDouble()),
                within(
                    1e-12 * Math.exp(x.toDouble()),
                ),
            )
            val roundTrip = BigMath.ln(BigMath.exp(x))
            assertThat(roundTrip.subtract(x).abs()).isLessThan(BigDecimal("1e-30"))
        }
        assertThat(BigMath.exp(BigDecimal.ZERO)).isEqualByComparingTo("1")
        assertThat(BigMath.ln(BigDecimal.ONE)).isEqualByComparingTo("0")
        assertThatThrownBy { BigMath.ln(BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

class CurveTest {
    private val asOf = LocalDate.parse("2026-09-30")
    private fun p(date: String, rate: String) = CurvePillar(LocalDate.parse(date), BigDecimal(rate))
    private val curve = Curve(
        CurveIndex.CZEONIA,
        asOf,
        listOf(p("2026-10-30", "0.030"), p("2027-03-30", "0.035"), p("2027-09-30", "0.040")),
    )

    @Test
    fun `DF is exactly one at asOf and strictly decreasing for positive rates`() {
        assertThat(curve.discountFactor(asOf)).isEqualByComparingTo("1")
        val dfs = (1L..1200L step 7).map { curve.discountFactor(asOf.plusDays(it)) }
        dfs.zipWithNext().forEach { (a, b) -> assertThat(b).isLessThan(a) }
        assertThat(dfs.first()).isLessThan(BigDecimal.ONE)
    }

    @Test
    fun `interpolation is exact at pillars, linear between and flat beyond both ends`() {
        curve.pillars.forEach { assertThat(curve.zeroRate(it.date)).isEqualByComparingTo(it.zeroRate) }
        // 2026-10-30 .. 2027-03-30 is 151 days; 2027-01-15 is 77 days in.
        val expected = BigDecimal(
            "0.030",
        ).add(BigDecimal("0.005").multiply(BigDecimal(77)).divide(BigDecimal(151), BigMath.MC))
        assertThat(
            curve.zeroRate(LocalDate.parse("2027-01-15")).subtract(expected).abs(),
        ).isLessThan(BigDecimal("1e-30"))
        assertThat(curve.zeroRate(asOf.plusDays(3))).isEqualByComparingTo("0.030")
        assertThat(curve.zeroRate(LocalDate.parse("2040-01-01"))).isEqualByComparingTo("0.040")
    }

    @Test
    fun `DF at a pillar is exp of minus r t on ACT-365F`() {
        val t = 365.0 / 365.0
        assertThat(
            curve.discountFactor(LocalDate.parse("2027-09-30")).toDouble(),
        ).isCloseTo(Math.exp(-0.04 * t), within(1e-15))
    }

    @Test
    fun `a parallel shift moves every zero rate by exactly the shift`() {
        val up = curve.parallelShift(BigDecimal(200))
        val down = curve.parallelShift(BigDecimal(-50))
        listOf("2026-10-01", "2026-12-01", "2027-06-01", "2031-01-01").map(LocalDate::parse).forEach { d ->
            assertThat(up.zeroRate(d).subtract(curve.zeroRate(d))).isEqualByComparingTo("0.02")
            assertThat(down.zeroRate(d).subtract(curve.zeroRate(d))).isEqualByComparingTo("-0.005")
        }
        assertThat(up.discountFactor(asOf.plusYears(1))).isLessThan(curve.discountFactor(asOf.plusYears(1)))
    }

    @Test
    fun `invalid pillars are rejected`() {
        assertThatThrownBy { Curve(CurveIndex.ESTR, asOf, emptyList()) }.hasMessageContaining("at least one pillar")
        assertThatThrownBy { Curve(CurveIndex.ESTR, asOf, listOf(p("2026-09-30", "0.01"))) }
            .hasMessageContaining("after asOf")
        assertThatThrownBy { Curve(CurveIndex.ESTR, asOf, listOf(p("2027-01-01", "0.01"), p("2027-01-01", "0.02"))) }
            .hasMessageContaining("strictly increasing")
        assertThatThrownBy { Curve(CurveIndex.ESTR, asOf, listOf(p("2027-06-01", "0.01"), p("2027-01-01", "0.02"))) }
            .hasMessageContaining("strictly increasing")
    }

    @Test
    fun `the average forward of a flat curve is the flat rate whatever the period length`() {
        val flat = Curve(CurveIndex.PRIBOR_3M, asOf, listOf(p("2027-09-30", "0.054")))
        assertThat(flat.averageForwardRate(asOf, LocalDate.parse("2026-10-31"))).isEqualByComparingTo("0.054")
        assertThat(flat.averageForwardRate(LocalDate.parse("2027-02-01"), LocalDate.parse("2027-03-01")))
            .isEqualByComparingTo("0.054")
        // ...whereas the simple forward depends on the period length (why projection does not use it).
        val simple1 = flat.forwardRate(asOf, asOf.plusDays(28))
        val simple2 = flat.forwardRate(asOf, asOf.plusDays(365))
        assertThat(simple1).isNotEqualByComparingTo(simple2)
    }
}

class CurveBootstrapTest {
    private val asOf = LocalDate.parse("2026-09-30")

    @Test
    fun `every quote reprices to par off the bootstrapped curve`() {
        val quotes = listOf(
            "ON" to "0.0350",
            "1W" to "0.0352",
            "1M" to "0.0355",
            "3M" to "0.0360",
            "6M" to "0.0365",
            "12M" to "0.0370",
        )
            .map { (t, r) -> MoneyMarketQuote(Tenor.parse(t), BigDecimal(r)) }
        val curve = CurveBootstrap.bootstrap(CurveIndex.CZEONIA, asOf, quotes)
        assertThat(curve.pillars).hasSize(quotes.size)
        quotes.forEach { q ->
            val end = q.tenor.endDate(asOf)
            // A deposit of 1 at the quote pays 1 + r·τ at `end`; its PV off the curve must be 1.
            val payoff = BigDecimal.ONE.add(q.simpleRate.multiply(Curve.yearFraction(asOf, end), BigMath.MC))
            val pv = payoff.multiply(curve.discountFactor(end), BigMath.MC)
            assertThat(pv.subtract(BigDecimal.ONE).abs()).isLessThan(BigDecimal("1e-25"))
            assertThat(curve.forwardRate(asOf, end).subtract(q.simpleRate).abs()).isLessThan(BigDecimal("1e-25"))
        }
    }

    @Test
    fun `tenors beyond one year and malformed quotes are refused`() {
        assertThatThrownBy { Tenor.parse("2Y") }.hasMessageContaining("exceeds one year")
        assertThatThrownBy { Tenor.parse("13M") }.hasMessageContaining("exceeds one year")
        assertThatThrownBy { Tenor.parse("3X") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CurveBootstrap.bootstrap(
                CurveIndex.ESTR,
                asOf,
                listOf(MoneyMarketQuote(Tenor.parse("1M"), BigDecimal("3.5"))),
            )
        }.hasMessageContaining("not a fraction")
        assertThatThrownBy {
            CurveBootstrap.bootstrap(
                CurveIndex.ESTR,
                asOf,
                listOf(
                    MoneyMarketQuote(Tenor.parse("12M"), BigDecimal("0.01")),
                    MoneyMarketQuote(Tenor.parse("1Y"), BigDecimal("0.02")),
                ),
            )
        }.hasMessageContaining("two quotes end")
    }
}
