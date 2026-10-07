// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Small-principal defects of [Amortization] (principal repaid per period ≈ a few minor units).
 * Every counterexample below produced a negative closing balance, a negative final payment or a
 * balloon on the pre-fix code (values in the case comments were measured on that code).
 */
class AmortizationSmallPrincipalTest {

    private val firstDue = LocalDate.parse("2026-01-31")

    private fun schedule(p: String, ccy: String, rate: String, n: Int, perYear: Int, m: AmortizationMethod) =
        Amortization.schedule(Money.of(p, ccy), BigDecimal(rate), n, firstDue, perYear, m)

    private fun assertClean(s: RepaymentSchedule, principal: String) {
        s.installments.forEach {
            assertThat(it.principal.amount.signum()).`as`("principal #%d", it.number).isGreaterThanOrEqualTo(0)
            assertThat(it.payment.amount.signum()).`as`("payment #%d", it.number).isGreaterThanOrEqualTo(0)
            assertThat(it.closingBalance.amount.signum()).`as`("closing #%d", it.number).isGreaterThanOrEqualTo(0)
        }
        assertThat(s.totalPrincipal.amount).isEqualByComparingTo(principal)
        assertThat(s.installments.last().closingBalance.amount).isEqualByComparingTo("0")
    }

    @ParameterizedTest(name = "{0} {1} / {3} x{4} @ {2} {5}")
    @CsvSource(
        // pre-fix: closing -1.40 at #47, last payment -1.56
        "39.94, EUR, 0.1134, 48, 1, ANNUITY",
        // pre-fix: negative from #153, last payment -0.33
        "32.01, CZK, 0, 155, 12, EQUAL_PRINCIPAL",
        // pre-fix: negative from #221, last payment -95
        "1100, JPY, 0, 240, 12, ANNUITY",
        "1100, JPY, 0, 240, 12, EQUAL_PRINCIPAL",
    )
    fun `counterexample schedules close cleanly`(p: String, ccy: String, rate: String, n: Int, py: Int, m: String) {
        assertClean(schedule(p, ccy, rate, n, py, AmortizationMethod.valueOf(m)), p)
    }

    @Test
    fun `high-rate small annuity no longer ends in a balloon`() {
        // pre-fix: regular 4.56, last 35.22 (the rounded payment never amortized the loan)
        val s = schedule("30.66", "CZK", "0.1487", 144, 1, AmortizationMethod.ANNUITY)
        assertClean(s, "30.66")
        val payments = s.installments.map { it.payment.amount }
        assertThat(payments.last()).isLessThan(BigDecimal("5.00"))
    }

    @Test
    fun `a regular amount below one minor unit is rejected`() {
        // pre-fix: 0.01 per period for 150 periods = 1.50 repaid on 1.00, last payment -0.49
        AmortizationMethod.entries.filter { it != AmortizationMethod.BULLET }.forEach { m ->
            assertThatThrownBy { schedule("1.00", "EUR", "0", 150, 12, m) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("below one minor unit")
        }
        // BULLET has no regular principal amount and stays valid.
        assertClean(schedule("1.00", "EUR", "0", 150, 12, AmortizationMethod.BULLET), "1.00")
    }

    @Test
    fun `rounded long-term annuity does not grow beyond Money range`() {
        // CI property-test counterexample: the rounded classic payment initially covers only
        // interest. Building every classic row before checking it grew the balance to 20 digits.
        val s = schedule("3680.655", "KWD", "0.3000", 191, 1, AmortizationMethod.ANNUITY)
        assertClean(s, "3680.655")
    }

    @Test
    fun `annuity whose rounded payment is below interest falls back before balance overflow`() {
        // The rounded level payment is below the first year's interest. The classic path
        // compounded a negative principal portion until Money rejected a 20-digit installment.
        assertClean(schedule("1473595", "JPY", "0.3000", 164, 1, AmortizationMethod.ANNUITY), "1473595")
    }

    @Test
    fun `annuity whose rounded principal exceeds opening falls back before balance overflow`() {
        // The fixed payment eventually overpays a nearly cleared balance. Continuing the
        // classic schedule from a negative closing balance overflowed Money years later.
        assertClean(schedule("161418", "JPY", "0.2500", 194, 1, AmortizationMethod.ANNUITY), "161418")
    }

    @Test
    fun `random schedules never go negative and repay exactly the principal`(): Unit = runBlocking {
        val ccyArb = Arb.element("EUR", "CZK", "JPY", "KWD")
        checkAll(
            PropTestConfig(iterations = 2000),
            ccyArb,
            Arb.long(1L, 5_000_000L),
            Arb.int(1, 360),
            Arb.int(0, 3000),
            Arb.element(1, 2, 4, 12),
            Arb.enum<AmortizationMethod>(),
        ) { ccy, minorUnits, n, rateBp, py, m ->
            val digits = java.util.Currency.getInstance(ccy).defaultFractionDigits
            val principal = BigDecimal(minorUnits).movePointLeft(digits)
            val rate = BigDecimal(rateBp).movePointLeft(4)
            val s = try {
                Amortization.schedule(Money.of(principal, ccy), rate, n, firstDue, py, m)
            } catch (e: IllegalArgumentException) {
                assertThat(e).hasMessageContaining("below one minor unit")
                assertThat(m).isNotEqualTo(AmortizationMethod.BULLET)
                return@checkAll
            }
            assertThat(s.installments).hasSize(n)
            assertClean(s, principal.toPlainString())
        }
    }
}
