// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random

/**
 * ADR-0318 phase 1: largest-remainder allocation and the rounding-policy registry.
 * The property tests use a fixed seed so a failure is reproducible.
 */
class MoneyAllocationTest {

    private fun minor(m: Money) = m.amount.movePointRight(m.currency.defaultFractionDigits).toBigIntegerExact()

    private fun assertInvariants(total: Money, ratios: List<BigDecimal>, parts: List<Money>) {
        assertThat(parts).hasSize(ratios.size)
        val sum = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
        assertThat(sum).isEqualByComparingTo(total.amount)
        val weightSum = ratios.fold(BigDecimal.ZERO, BigDecimal::add)
        val totalMinor = BigDecimal(minor(total))
        parts.forEachIndexed { i, p ->
            assertThat(p.currency).isEqualTo(total.currency)
            val exact = totalMinor.multiply(ratios[i]).divide(weightSum, MathContext.DECIMAL128)
            val diff = BigDecimal(minor(p)).subtract(exact).abs()
            assertThat(diff).describedAs("part $i=$p exact=$exact").isLessThan(BigDecimal.ONE)
        }
    }

    private fun randomRatio(rnd: Random): BigDecimal =
        BigDecimal.valueOf(rnd.nextInt(0, 1_000).toLong(), rnd.nextInt(0, 3))

    @Test
    fun `random allocations preserve the sum and stay within one minor unit, for 0, 2 and 3 decimal currencies`() {
        val rnd = Random(318)
        for (code in listOf("JPY", "EUR", "CZK", "BHD", "KWD")) {
            val digits = CurrencyCode.of(code).defaultFractionDigits
            repeat(2_000) {
                val total = Money.of(BigDecimal.valueOf(rnd.nextLong(-10_000_000_000L, 10_000_000_000L), digits), code)
                val drawn = List(rnd.nextInt(1, 12)) { randomRatio(rnd) }
                val ratios = if (drawn.all { r -> r.signum() == 0 }) drawn + BigDecimal.ONE else drawn
                assertInvariants(total, ratios, total.allocate(ratios))
            }
        }
    }

    @Test
    fun `random splits preserve the sum and differ by at most one minor unit`() {
        val rnd = Random(10929)
        for (code in listOf("JPY", "EUR", "KWD")) {
            val digits = CurrencyCode.of(code).defaultFractionDigits
            repeat(2_000) {
                val total = Money.of(BigDecimal.valueOf(rnd.nextLong(-1_000_000_000L, 1_000_000_000L), digits), code)
                val n = rnd.nextInt(1, 50)
                val parts = total.split(n)
                assertInvariants(total, List(n) { BigDecimal.ONE }, parts)
                val m = parts.map { minor(it) }
                assertThat(m.max() - m.min()).isLessThanOrEqualTo(java.math.BigInteger.ONE)
            }
        }
    }

    @Test
    fun `ties go to the lower index first`() {
        assertThat(Money.of("100.00", "EUR").split(3).map { it.amount.toPlainString() })
            .containsExactly("33.34", "33.33", "33.33")
        assertThat(Money.of("0.02", "EUR").split(3).map { it.amount.toPlainString() })
            .containsExactly("0.01", "0.01", "0.00")
        assertThat(Money.of(BigDecimal("5"), "JPY").split(3).map { it.amount.toPlainString() })
            .containsExactly("2", "2", "1")
    }

    @Test
    fun `the largest remainder wins over position`() {
        // 0.05 EUR over 3:7 -> exact 1.5 / 3.5 minor units; tie on .5, index 0 gets it.
        assertThat(Money.of("0.05", "EUR").allocate(3, 7).map { it.amount.toPlainString() })
            .containsExactly("0.02", "0.03")
        // 10 minor units over 1:1:8 -> 1, 1, 8 exact; and 11 over 1:1:8 -> 1.1, 1.1, 8.8: index 2 has largest remainder.
        assertThat(Money.of("0.11", "EUR").allocate(1, 1, 8).map { it.amount.toPlainString() })
            .containsExactly("0.01", "0.01", "0.09")
    }

    @Test
    fun `negative amounts allocate as the mirror image of the positive`() {
        val pos = Money.of("100.000", "BHD").allocate(1, 1, 1)
        val neg = Money.of("-100.000", "BHD").allocate(1, 1, 1)
        assertThat(neg.map { it.amount }).isEqualTo(pos.map { it.amount.negate() })
        assertThat(neg.map { it.amount.toPlainString() }).containsExactly("-33.334", "-33.333", "-33.333")
    }

    @Test
    fun `zero ratio entries receive zero and the rest still sum`() {
        assertThat(Money.of("1.00", "EUR").allocate(0, 1, 0, 2).map { it.amount.toPlainString() })
            .containsExactly("0.00", "0.33", "0.00", "0.67")
    }

    @Test
    fun `invalid ratios are rejected`() {
        val m = Money.of("1.00", "EUR")
        assertThatThrownBy { m.allocate(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { m.allocate(0, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { m.allocate(1, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { m.split(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { m.split(-2) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rounding policies mirror today's call-site behaviour`() {
        val eur = CurrencyCode.of("EUR")
        val czk = CurrencyCode.of("CZK")
        assertThat(RoundingPolicy.LEDGER_POSTING.round(BigDecimal("1.005"), eur)).isEqualByComparingTo("1.00")
        assertThat(RoundingPolicy.DISPLAY.round(BigDecimal("1.015"), eur)).isEqualByComparingTo("1.02")
        assertThat(RoundingPolicy.FX_AMOUNT.round(BigDecimal("1.005"), eur)).isEqualByComparingTo("1.01")
        assertThat(RoundingPolicy.FEE.round(BigDecimal("1.005"), eur)).isEqualByComparingTo("1.01")
        assertThat(
            RoundingPolicy.INTEREST_ACCRUAL.round(BigDecimal("0.0000125"), eur).toPlainString(),
        ).isEqualTo("0.000013")
        assertThat(RoundingPolicy.FX_RATE.round(BigDecimal("0.123456785"), eur).toPlainString()).isEqualTo("0.12345679")
        assertThat(RoundingPolicy.TAX_WITHHOLDING.round(BigDecimal("150.99"), czk).toPlainString()).isEqualTo("150")
        assertThat(RoundingPolicy.TAX_WITHHOLDING.mode).isEqualTo(RoundingMode.DOWN)
    }

    @Test
    fun `Money round applies a policy and refuses a finer scale than the currency holds`() {
        assertThat(
            Money.of("150.99", "CZK").round(RoundingPolicy.TAX_WITHHOLDING).amount.toPlainString(),
        ).isEqualTo("150")
        assertThat(
            Money.of("1.00", "EUR").round(RoundingPolicy.LEDGER_POSTING).amount.toPlainString(),
        ).isEqualTo("1.00")
        assertThatThrownBy { Money.of("1.00", "EUR").round(RoundingPolicy.INTEREST_ACCRUAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
