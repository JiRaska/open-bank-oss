// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.mapper.C0100Mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class C0100MapperTest {

    private val knownRows = setOf("r010", "r015", "r020", "r030", "r130", "r160", "r300", "r530", "r750")

    @Test
    fun `C 01_00 reports every own-funds row as an explicit flagged zero when the ledger has no capital accounts`() {
        // Realistic fixture trial balance for this platform today: cash, nostro, customer
        // deposits, interest and fee income/expense — no EQUITY-typed lines, because the
        // ledger's chart of accounts does not seed any capital-structure accounts.
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
            TrialBalanceLineDto(code = "1001", accountType = "ASSET", net = BigDecimal("120000"), currency = "CZK"),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("580000"), currency = "CZK"),
            TrialBalanceLineDto(code = "3000", accountType = "INCOME", net = BigDecimal("15000"), currency = "CZK"),
            TrialBalanceLineDto(code = "4000", accountType = "EXPENSE", net = BigDecimal("5000"), currency = "CZK"),
        )

        val template = C0100Mapper.map(lines, LocalDate.of(2026, 6, 30))

        assertThat(template.templateId).isEqualTo("C_01.00")
        assertThat(template.period).isEqualTo(LocalDate.of(2026, 6, 30))
        assertThat(template.hasDataGaps).isTrue()

        // Every own-funds row must be PRESENT — never silently omitted — even though the
        // platform has no real capital data to populate it with.
        assertThat(template.cells.map { it.rowRef }).containsExactlyInAnyOrderElementsOf(knownRows)

        template.cells.forEach { cell ->
            assertThat(cell.isDataGap)
                .withFailMessage("row ${cell.rowRef} should be flagged as a data gap (no capital accounts exist)")
                .isTrue()
            assertThat(cell.value)
                .withFailMessage("row ${cell.rowRef} should report an explicit zero, not a derived/guessed value")
                .isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(cell.gapReason).isNotBlank()
        }
    }

    @Test
    fun `C 01_00 derives each own-funds subtotal from its explicit capital source accounts`() {
        val lines = listOf(
            TrialBalanceLineDto(code = "6000", accountType = "EQUITY", net = BigDecimal("-10000000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6010", accountType = "EQUITY", net = BigDecimal("-2000000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6020", accountType = "EQUITY", net = BigDecimal("-500000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6030", accountType = "EQUITY", net = BigDecimal("-1000000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6040", accountType = "EQUITY", net = BigDecimal("250000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6050", accountType = "EQUITY", net = BigDecimal("-1000000"), currency = "CZK"),
            TrialBalanceLineDto(code = "6060", accountType = "EQUITY", net = BigDecimal("-2000000"), currency = "CZK"),
        )

        val template = C0100Mapper.map(lines, LocalDate.of(2026, 6, 30))

        assertThat(template.hasDataGaps).isFalse()
        template.cells.forEach { cell ->
            assertThat(cell.isDataGap).isFalse()
            assertThat(cell.gapReason).isNull()
        }
        assertThat(template.cells.associate { it.rowRef to it.value }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "r010" to BigDecimal("16250000"),
                "r015" to BigDecimal("14250000"),
                "r020" to BigDecimal("13250000"),
                "r030" to BigDecimal("12000000"),
                "r130" to BigDecimal("500000"),
                "r160" to BigDecimal("1000000"),
                "r300" to BigDecimal("250000"),
                "r530" to BigDecimal("1000000"),
                "r750" to BigDecimal("2000000"),
            ),
        )
    }

    @Test
    fun `C 01_00 ignores unrelated equity accounts instead of silently treating them as regulatory capital`() {
        val lines = listOf(
            TrialBalanceLineDto(code = "6999", accountType = "EQUITY", net = BigDecimal("-999999"), currency = "CZK"),
        )

        val template = C0100Mapper.map(lines, LocalDate.of(2026, 6, 30))

        assertThat(template.hasDataGaps).isTrue()
        assertThat(template.cells).allSatisfy { cell ->
            assertThat(cell.value).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(cell.isDataGap).isTrue()
        }
    }

    @Test
    fun `C 01_00 handles an empty trial balance without omitting any row`() {
        val template = C0100Mapper.map(emptyList(), LocalDate.of(2026, 6, 30))

        assertThat(template.cells.map { it.rowRef }).containsExactlyInAnyOrderElementsOf(knownRows)
        assertThat(template.hasDataGaps).isTrue()
        template.cells.forEach { cell -> assertThat(cell.value).isEqualByComparingTo(BigDecimal.ZERO) }
    }
}
