// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The APRC is the number a customer compares lenders on and a regulator checks, so these tests
 * pin it against cases with an independently known answer rather than against this implementation's
 * own output.
 */
class AprcTest {

    private fun years(months: Int): BigDecimal =
        BigDecimal(months).divide(BigDecimal(12), java.math.MathContext.DECIMAL128)

    private fun advance(amount: String) = Aprc.CashFlow(BigDecimal.ZERO, BigDecimal(amount))

    private fun monthlyPayments(amount: String, count: Int) =
        (1..count).map { Aprc.CashFlow(years(it), BigDecimal(amount)) }

    private fun percent(rate: BigDecimal?): BigDecimal? =
        rate?.multiply(BigDecimal(100))?.setScale(2, RoundingMode.HALF_UP)

    // ── Known answers ─────────────────────────────────────────────────────────

    @Test
    fun `a single repayment of 110 on an advance of 100 after one year is 10 percent`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("110"))))
        assertThat(percent(aprc)).isEqualByComparingTo("10.00")
    }

    @Test
    fun `doubling the money in one year is 100 percent`() {
        val aprc = Aprc.solve(listOf(advance("1000")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("2000"))))
        assertThat(percent(aprc)).isEqualByComparingTo("100.00")
    }

    @Test
    fun `repaying exactly what was advanced, at the moment it was advanced, is zero cost and has no positive rate`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100"))))
        assertThat(aprc).isNull()
    }

    @Test
    fun `an interest-free loan repaid later has no positive solution and must not be reported as zero`() {
        // Repaying 100 a year after receiving 100: the equation balances only at X = 0, which the
        // solver reports as "no solution" rather than inventing a rate. Null must reach the caller —
        // rendering it as 0% would read to a customer as free credit that has actually been priced.
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("100"))))
        assertThat(aprc).isNull()
    }

    // ── The property that matters: charges raise the APRC above the nominal rate ─

    @Test
    fun `an arrangement fee pushes the APRC above the nominal rate`() {
        // 12 monthly payments of 8,884.88 on 100,000 is ~6.5% nominal. Adding a 2,000 fee at
        // drawdown means the customer receives 98,000 for the same repayments, so the true cost
        // rises — this is the entire reason the APRC exists and the case a nominal-rate shortcut
        // gets wrong.
        val withoutFee = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 12))
        val withFee = Aprc.solve(listOf(advance("98000")), monthlyPayments("8884.88", 12))
        assertThat(withFee).isGreaterThan(withoutFee)
    }

    @Test
    fun `a longer term at the same instalment costs less per year`() {
        val short = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 12))
        val long = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 18))
        assertThat(long).isGreaterThan(short)
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    fun `paying back less than was advanced has no positive solution`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("50"))))
        assertThat(aprc).isNull()
    }

    @Test
    fun `a negative payment is refused rather than silently inverting the equation`() {
        assertThatThrownBy {
            Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("-110"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a cash flow before the first drawdown is refused`() {
        assertThatThrownBy {
            Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal("-1"), BigDecimal("110"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `no payments at all is refused, not answered with zero`() {
        assertThatThrownBy { Aprc.solve(listOf(advance("100")), emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `the same inputs always produce the same rate to the disclosed precision`() {
        val first = Aprc.solve(listOf(advance("250000")), monthlyPayments("6100.55", 48))
        val second = Aprc.solve(listOf(advance("250000")), monthlyPayments("6100.55", 48))
        assertThat(first).isEqualByComparingTo(second)
        assertThat(first!!.scale()).isEqualTo(6)
    }
}
