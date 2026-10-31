// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.mapper.F0102Mapper
import com.openbank.finrep.domain.mapper.F0103Mapper
import com.openbank.finrep.domain.mapper.F0200Mapper
import com.openbank.finrep.domain.model.FinrepTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** ADR-0344 instrument chart (ledger V35) lands in the IFRS 9 rows it belongs to (#14284). */
class TreasuryInstrumentGlMappingTest {
    private val asOf: LocalDate = LocalDate.of(2026, 6, 30)

    private fun line(code: String, type: String, net: String) =
        TrialBalanceLineDto(code = code, accountType = type, net = BigDecimal(net), currency = "CZK")

    private fun snapshot(vararg lines: TrialBalanceLineDto) =
        TrialBalanceSnapshot(lines = lines.toList(), ledgerReportsBalanced = true)

    private fun FinrepTemplate.cell(row: String): BigDecimal = cells.single { it.rowRef == row }.value

    private val book = snapshot(
        line("1600", "ASSET", "100"), // AC securities
        line("1630", "ASSET", "-2"), // discount
        line("1640", "ASSET", "3"), // accrued
        line("1610", "ASSET", "50"), // FVOCI
        line("1620", "ASSET", "20"), // FVTPL
        line("1700", "ASSET", "7"), // derivative FV
        line("1660", "ASSET", "40"), // treasury loan
        line("1680", "ASSET", "30"), // reverse repo
        line("1690", "ASSET", "5"), // cash in transit
        line("9000", "ASSET", "1000"), // memo
        line("9100", "LIABILITY", "-1000"), // memo contra
        line("2430", "LIABILITY", "-60"), // repo payable
        line("2400", "LIABILITY", "-4"), // derivative liability
        line("3100", "EQUITY", "-6"), // FVOCI reserve
        line("6000", "EQUITY", "-161"), // capital, so the trial balance ties out
        line("4220", "INCOME", "-9"),
        line("4210", "INCOME", "-11"),
        line("5240", "EXPENSE", "2"),
        line("5230", "EXPENSE", "1"),
        line("4300", "INCOME", "-8"),
        line("5310", "EXPENSE", "3"),
    )

    @Test
    fun `F01_01 adds AC securities to r0181, every book to total assets, and leaves memo accounts out`() {
        val t = F0101Mapper.map(book, asOf)
        assertThat(t.dataGaps).isEmpty()
        assertThat(t.cell("r0183")).isEqualByComparingTo("70")
        assertThat(t.cell("r0181")).isEqualByComparingTo("171")
        assertThat(t.cell("r0360")).isEqualByComparingTo("5")
        // 253 on-balance assets; the 1000 memo notional must not reach total assets.
        assertThat(t.cell("r0380")).isEqualByComparingTo("253")
    }

    @Test
    fun `F01_02 reports repo as deposits and derivatives in the total, without memo contra`() {
        val t = F0102Mapper.map(book, asOf)
        assertThat(t.dataGaps).isEmpty()
        assertThat(t.cell("r0110")).isEqualByComparingTo("60")
        assertThat(t.cell("r0300")).isEqualByComparingTo("64")
    }

    @Test
    fun `F01_03 includes the FVOCI reserve in equity and ties to total assets`() {
        val t = F0103Mapper.map(book, asOf)
        assertThat(t.dataGaps).isEmpty()
        assertThat(t.cell("r0300")).isEqualByComparingTo("189")
        assertThat(t.cell("r0310")).isEqualByComparingTo(F0101Mapper.map(book, asOf).cell("r0380"))
    }

    @Test
    fun `F02_00 separates amortised-cost interest and nets treasury losses in operating income`() {
        val t = F0200Mapper.map(book, asOf)
        assertThat(t.dataGaps).isEmpty()
        assertThat(t.cell("r0051")).isEqualByComparingTo("9")
        assertThat(t.cell("r0010")).isEqualByComparingTo("20")
        assertThat(t.cell("r0120")).isEqualByComparingTo("2")
        assertThat(t.cell("r0090")).isEqualByComparingTo("3")
        assertThat(t.cell("r0355")).isEqualByComparingTo("22")
        assertThat(t.cell("r0670")).isEqualByComparingTo("22")
    }
}
