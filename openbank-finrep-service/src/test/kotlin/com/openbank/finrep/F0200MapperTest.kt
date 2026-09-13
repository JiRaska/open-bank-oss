// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0200Mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** See `F0101MapperTest` for the sign convention and for why the balance assertions come in pairs. */
class F0200MapperTest {

    private val asOf: LocalDate = LocalDate.of(2026, 6, 30)

    private fun balancedTrialBalance() = listOf(
        line("1000", "ASSET", "460000"),
        line("2000", "LIABILITY", "-420000"),
        line("4100", "INCOME", "-120000"),
        line("5100", "EXPENSE", "80000"),
    )

    @Test
    fun `F02_00 reports profit for the year at its official datapoint`() {
        val template = F0200Mapper.map(tb(balancedTrialBalance(), ledgerSays = true), asOf)

        assertThat(template.templateId).isEqualTo("F02.00")
        assertThat(template.cells).hasSize(13)
        assertThat(cell(template, "r0670")).isEqualByComparingTo("40000")
        assertThat(template.cells).allMatch { it.colRef == "c0010" }
        assertThat(template.hasDataGaps).isFalse()
    }

    @Test
    fun `a P&L drawn off a trial balance that ties out is reported as balanced`() {
        assertThat(F0200Mapper.map(tb(balancedTrialBalance(), ledgerSays = true), asOf).isBalanced).isTrue()
    }

    @Test
    fun `a P&L drawn off a trial balance that does NOT tie out is reported as unbalanced`() {
        // Deliberately introduced on the BALANCE-SHEET side, which F02.00 does not report at all:
        // the flag is a statement about the render's input, not about the three rows above it.
        // A P&L is not submittable off a GL that does not balance, however self-consistent it looks.
        val lines = balancedTrialBalance() + line("1001", "ASSET", "25000")

        assertThat(F0200Mapper.map(tb(lines, ledgerSays = false), asOf).isBalanced).isFalse()
    }

    @Test
    fun `profit for the year stays a definition and can never falsify the flag on its own`() {
        val lines = balancedTrialBalance()
        val template = F0200Mapper.map(tb(lines, ledgerSays = true), asOf)

        assertThat(cell(template, "r0670")).isEqualByComparingTo("40000")
    }

    /** Ledger's verdict is explicit per call site, never derived from `lines` (issue #6011). */
    private fun tb(lines: List<TrialBalanceLineDto>, ledgerSays: Boolean?) =
        TrialBalanceSnapshot(lines = lines, ledgerReportsBalanced = ledgerSays)

    private fun line(code: String, accountType: String, net: String, currency: String = "CZK") =
        TrialBalanceLineDto(code = code, accountType = accountType, net = BigDecimal(net), currency = currency)

    private fun cell(template: com.openbank.finrep.domain.model.FinrepTemplate, rowRef: String) =
        template.cells.single { it.rowRef == rowRef }.value
}
