// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurvePillar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The shock formulas against the worked examples printed in BCBS d368 (April 2016), Annex 2,
 * "Examples", which evaluate them at t_k = 3.5 years:
 *   S_short(3.5) = e^(−3.5/4) = 0.417; a +100 bp short shock → +41.7 bp;
 *   steepener with |short| = |long| = 100 bp (JPY) → +25.4 bp; flattener → −1.6 bp.
 */
class SupervisoryShocksTest {

    private val t35 = BigDecimal("3.5")
    private val jpy = ShockSizes(BigDecimal(100), BigDecimal(100), BigDecimal(100))
    private val eur = ShockSizes.parse("200/250/100") // d368 Annex 2 Table 1, EUR column
    private val asOf = LocalDate.parse("2026-09-30")

    private fun r1(x: BigDecimal) = x.setScale(1, RoundingMode.HALF_EVEN)

    @Test
    fun `short scaling at 3,5 years is 0,417 (d368 example)`() {
        assertThat(SupervisoryShocks.shortScale(t35).setScale(3, RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.417")
    }

    @Test
    fun `short up of 100 bp is 41,7 bp at 3,5 years (d368 example)`() {
        val sizes = ShockSizes(BigDecimal.ZERO, BigDecimal(100), BigDecimal.ZERO)
        assertThat(r1(SupervisoryShocks.shockBp(ShockScenario.SHORT_UP, sizes, t35))).isEqualByComparingTo("41.7")
        assertThat(r1(SupervisoryShocks.shockBp(ShockScenario.SHORT_DOWN, sizes, t35))).isEqualByComparingTo("-41.7")
    }

    @Test
    fun `steepener and flattener reproduce the d368 JPY example`() {
        assertThat(r1(SupervisoryShocks.shockBp(ShockScenario.STEEPENER, jpy, t35))).isEqualByComparingTo("25.4")
        assertThat(r1(SupervisoryShocks.shockBp(ShockScenario.FLATTENER, jpy, t35))).isEqualByComparingTo("-1.6")
    }

    @Test
    fun `parallel is flat and short is the full size at t=0`() {
        listOf("0", "1", "10", "30").map(::BigDecimal).forEach { t ->
            assertThat(SupervisoryShocks.shockBp(ShockScenario.PARALLEL_UP, eur, t)).isEqualByComparingTo("200")
            assertThat(SupervisoryShocks.shockBp(ShockScenario.PARALLEL_DOWN, eur, t)).isEqualByComparingTo("-200")
        }
        assertThat(SupervisoryShocks.shockBp(ShockScenario.SHORT_UP, eur, BigDecimal.ZERO)).isEqualByComparingTo("250")
        // Steepener at t=0 is −0.65 · 250 = −162.5; the long leg is zero there.
        assertThat(
            SupervisoryShocks.shockBp(ShockScenario.STEEPENER, eur, BigDecimal.ZERO),
        ).isEqualByComparingTo("-162.5")
    }

    @ParameterizedTest
    @EnumSource(ShockScenario::class)
    fun `a zero shock leaves the curve exactly as it was`(scenario: ShockScenario) {
        val base = Curve(
            CurveIndex.ESTR,
            asOf,
            listOf(
                CurvePillar(asOf.plusYears(1), BigDecimal("0.02")),
                CurvePillar(asOf.plusYears(10), BigDecimal("0.03")),
            ),
        )
        val shocked = SupervisoryShocks.shock(base, scenario, ShockSizes.parse("0/0/0"), PostShockFloor.parse("-150/3"))
        listOf(asOf.plusDays(3), asOf.plusYears(2), asOf.plusYears(20)).forEach { d ->
            assertThat(shocked.zeroRate(d)).isEqualTo(base.zeroRate(d))
            assertThat(shocked.discountFactor(d)).isEqualTo(base.discountFactor(d))
        }
    }

    @Test
    fun `the shift is applied at each date, not only at pillars`() {
        val base = Curve(CurveIndex.ESTR, asOf, listOf(CurvePillar(asOf.plusYears(1), BigDecimal("0.02"))))
        val shocked = SupervisoryShocks.shock(base, ShockScenario.SHORT_UP, eur, null)
        val d = asOf.plusDays(1278) // 3.50137 years
        val expected = BigDecimal("0.02").add(
            SupervisoryShocks.shockBp(ShockScenario.SHORT_UP, eur, Curve.yearFraction(asOf, d)).movePointLeft(4),
        )
        assertThat(shocked.zeroRate(d)).isEqualByComparingTo(expected)
        assertThat(
            shocked.zeroRate(d).subtract(BigDecimal("0.02")).movePointRight(4).toDouble(),
        ).isBetween(104.0, 105.0)
    }

    @Test
    fun `the post-shock floor binds and never pushes a rate below its base`() {
        val floor = PostShockFloor.parse("-150/3")
        val down = ShockSizes.parse("400/0/0")
        // base +0.10 %, parallel −400 bp → −3.90 %; floor at t=0 is −1.50 %.
        assertThat(
            floor.apply(BigDecimal.ZERO, BigDecimal("0.001"), BigDecimal("-0.039")),
        ).isEqualByComparingTo("-0.015")
        // at 10y the floor is −150 + 30 = −120 bp
        assertThat(floor.at(BigDecimal.TEN)).isEqualByComparingTo("-0.012")
        // beyond 50y it is capped at zero
        assertThat(floor.at(BigDecimal(60))).isEqualByComparingTo("0")
        // a base already below the floor stays where it is (the shock would move it further)
        assertThat(floor.apply(BigDecimal.ZERO, BigDecimal("-0.02"), BigDecimal("-0.06"))).isEqualByComparingTo("-0.02")
        // and an up-shock is never touched by the floor
        assertThat(floor.apply(BigDecimal.ZERO, BigDecimal("-0.02"), BigDecimal("0.00"))).isEqualByComparingTo("0.00")

        val base = Curve(CurveIndex.ESTR, asOf, listOf(CurvePillar(asOf.plusYears(1), BigDecimal("0.001"))))
        val floored = SupervisoryShocks.shock(base, ShockScenario.PARALLEL_DOWN, down, floor)
        val unfloored = SupervisoryShocks.shock(base, ShockScenario.PARALLEL_DOWN, down, null)
        val d = asOf.plusDays(1)
        assertThat(unfloored.zeroRate(d)).isEqualByComparingTo("-0.039")
        assertThat(floored.zeroRate(d)).isGreaterThan(unfloored.zeroRate(d))
        assertThat(floored.zeroRate(d).toDouble()).isBetween(-0.0150, -0.0149)
    }

    @Test
    fun `invalid parameters are refused rather than guessed`() {
        assertThatThrownBy { PostShockFloor.parse("50/3") }.hasMessageContaining("above zero")
        assertThatThrownBy { ShockSizes.parse("200/250") }.hasMessageContaining("parallel/short/long")
        assertThatThrownBy { ShockSizes.parse("-1/0/0") }.hasMessageContaining("negative")
    }
}
