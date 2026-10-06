// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Golden set: 20 ordinary schedules captured from [Amortization] BEFORE the small-principal fix
 * (`amortization-golden.txt`). The fix must change only degenerate schedules, so every line here
 * is asserted byte-identical.
 */
class AmortizationGoldenTest {

    internal data class Case(
        val principal: String,
        val currency: String,
        val rate: String,
        val periods: Int,
        val perYear: Int,
        val method: AmortizationMethod,
    )

    internal companion object {
        val CASES = listOf(
            Case("100000.00", "CZK", "0.069", 12, 12, AmortizationMethod.ANNUITY),
            Case("250000.00", "CZK", "0.0549", 60, 12, AmortizationMethod.ANNUITY),
            Case("12000.00", "EUR", "0.12", 12, 12, AmortizationMethod.ANNUITY),
            Case("5000.00", "EUR", "0.0899", 24, 12, AmortizationMethod.ANNUITY),
            Case("3000000", "JPY", "0.015", 36, 12, AmortizationMethod.ANNUITY),
            Case("1000.000", "KWD", "0.045", 12, 12, AmortizationMethod.ANNUITY),
            Case("80000.00", "EUR", "0.0", 48, 12, AmortizationMethod.ANNUITY),
            Case("150000.00", "EUR", "0.036", 20, 4, AmortizationMethod.ANNUITY),
            Case("40000.00", "CZK", "0.1134", 10, 2, AmortizationMethod.ANNUITY),
            Case("1234567.89", "CZK", "0.0425", 360, 12, AmortizationMethod.ANNUITY),
            Case("100000.00", "CZK", "0.069", 12, 12, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("10000.00", "EUR", "0.07", 7, 12, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("999.99", "EUR", "0.05", 36, 12, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("500000", "JPY", "0.02", 60, 12, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("2500.500", "KWD", "0.06", 8, 4, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("60000.00", "EUR", "0.0", 24, 12, AmortizationMethod.EQUAL_PRINCIPAL),
            Case("100000.00", "CZK", "0.069", 12, 12, AmortizationMethod.BULLET),
            Case("20000.00", "EUR", "0.04", 6, 2, AmortizationMethod.BULLET),
            Case("750000", "JPY", "0.03", 5, 1, AmortizationMethod.BULLET),
            Case("333.33", "EUR", "0.1", 3, 3, AmortizationMethod.ANNUITY),
        )

        fun render(c: Case): String {
            val s = Amortization.schedule(
                principal = Money.of(c.principal, c.currency),
                nominalAnnualRate = BigDecimal(c.rate),
                termPeriods = c.periods,
                firstDueDate = LocalDate.parse("2026-01-31"),
                periodsPerYear = c.perYear,
                method = c.method,
            )
            return buildString {
                append("# ").append(c).append('\n')
                s.installments.forEach {
                    append(
                        listOf(
                            it.number,
                            it.dueDate,
                            it.openingBalance.amount.toPlainString(),
                            it.principal.amount.toPlainString(),
                            it.interest.amount.toPlainString(),
                            it.payment.amount.toPlainString(),
                            it.closingBalance.amount.toPlainString(),
                        ).joinToString("|"),
                    ).append('\n')
                }
            }
        }
    }

    @Test
    fun `ordinary schedules are byte-identical to the pre-fix golden capture`() {
        val golden = javaClass.getResource("/amortization-golden.txt")!!.readText()
        val now = CASES.joinToString("") { render(it) }
        assertThat(now).isEqualTo(golden)
    }
}
