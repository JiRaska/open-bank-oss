// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.infrastructure.returns.pension.CompanyBooksCalculator
import com.openbank.tax.infrastructure.returns.pension.FrozenTrialBalanceDto
import com.openbank.tax.infrastructure.returns.pension.FrozenTrialBalanceSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Golden company books (ADR-0337): 2024 closed as a frozen YEAR, then January–March 2025 as frozen
 * MONTHs — March a loss month that also pays off the 2024 payable. The balance at 31 March is the
 * sum of all four; the YTD P&L only of the 2025 months.
 */
class CompanyBooksCalculatorTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    private fun load(name: String): FrozenTrialBalanceDto = mapper.readValue(
        CompanyBooksCalculatorTest::class.java.classLoader.getResource("company-ledger/$name.json")!!.readText(),
    )

    private val requested = mutableListOf<String>()

    private val ledger = FrozenTrialBalanceSource { type, date ->
        requested += "$type $date"
        when {
            type == "YEAR" && date.year == 2024 -> load("YEAR-2024")
            type == "MONTH" && date.year == 2025 && date.monthValue <= 3 ->
                load("MONTH-2025-%02d".format(date.monthValue))
            else -> throw ReturnDataUnavailableException("409 $type $date not frozen")
        }
    }

    private fun figures(end: String, source: FrozenTrialBalanceSource = ledger, opened: String = "2024-01-01") =
        runBlocking { CompanyBooksCalculator.figures(source, LocalDate.parse(opened), "CZK", LocalDate.parse(end)) }

    @Test
    fun `the March balance sheet sums every frozen period since the books opened, and balances`() {
        val f = figures("2025-03-31")
        assertThat(f.totalAssets).isEqualByComparingTo("50820000")
        assertThat(f.totalLiabilities).isEqualByComparingTo("0")
        assertThat(f.totalEquity).isEqualByComparingTo("50820000")
        assertThat(f.totalAssets).isEqualByComparingTo(f.totalLiabilities + f.totalEquity)
        // Year to date: 2025 months only — a loss, with income and expenses each non-negative.
        assertThat(f.incomeYtd).isEqualByComparingTo("830000")
        assertThat(f.expensesYtd).isEqualByComparingTo("910000")
        assertThat(
            requested,
        ).containsExactly("YEAR 2024-01-01", "MONTH 2025-01-01", "MONTH 2025-02-01", "MONTH 2025-03-01")
        assertThat(f.evidence.keys).containsExactly("YEAR:2024", "MONTH:2025-01", "MONTH:2025-02", "MONTH:2025-03")
    }

    @Test
    fun `a period not frozen refuses the balance rather than summing over the gap`() {
        assertThatThrownBy { figures("2025-04-30") }.isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("MONTH 2025-04-01")
        // Books opened earlier than the first frozen year: 2023 is a gap.
        assertThatThrownBy { figures("2025-03-31", opened = "2023-06-01") }
            .isInstanceOf(ReturnDataUnavailableException::class.java).hasMessageContaining("YEAR 2023")
        assertThatThrownBy { figures("2025-03-15") }.isInstanceOf(ReturnDataUnavailableException::class.java)
        assertThatThrownBy { figures("2023-12-31") }.isInstanceOf(ReturnDataUnavailableException::class.java)
    }

    @Test
    fun `an unbalanced, flag-less, foreign-currency or unknown-type period is refused`() {
        fun tampered(f: (FrozenTrialBalanceDto) -> FrozenTrialBalanceDto) = FrozenTrialBalanceSource { t, d ->
            val tb = ledger.frozen(t, d)
            if (t == "MONTH" && d.monthValue == 2) f(tb) else tb
        }
        assertThatThrownBy { figures("2025-03-31", tampered { it.copy(balanced = false) }) }
            .hasMessageContaining("not reported balanced")
        assertThatThrownBy { figures("2025-03-31", tampered { it.copy(balanced = null) }) }
            .hasMessageContaining("not reported balanced")
        assertThatThrownBy {
            figures("2025-03-31", tampered { tb -> tb.copy(lines = tb.lines.map { it.copy(currency = "EUR") }) })
        }.hasMessageContaining("EUR")
        assertThatThrownBy {
            figures("2025-03-31", tampered { tb -> tb.copy(lines = tb.lines.map { it.copy(type = "MEMO") }) })
        }.hasMessageContaining("unknown account type")
    }

    @Test
    fun `books opened inside the reported year sum only the months since opening`() {
        val f = figures("2025-03-31", opened = "2025-02-01")
        assertThat(requested).containsExactly("MONTH 2025-02-01", "MONTH 2025-03-01")
        assertThat(f.incomeYtd).isEqualByComparingTo("530000")
    }
}
