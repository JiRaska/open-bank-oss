// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0102Mapper
import com.openbank.finrep.domain.mapper.F0103Mapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class F0102AndF0103MapperTest {
    private val asOf = LocalDate.of(2026, 6, 30)
    private val snapshot = TrialBalanceSnapshot(
        lines = listOf(
            line("1000", "ASSET", "500000"),
            line("2000", "LIABILITY", "-300000"),
            line("4100", "INCOME", "-260000"),
            line("5100", "EXPENSE", "60000"),
        ),
        ledgerReportsBalanced = true,
    )

    @Test
    fun `F01_02 uses the official total-liabilities cell`() {
        val template = F0102Mapper.map(snapshot, asOf)

        assertThat(template.templateId).isEqualTo("F01.02")
        val cell = template.cells.single { it.rowRef == "r0300" }
        assertThat(cell.rowRef).isEqualTo("r0300")
        assertThat(cell.colRef).isEqualTo("c0010")
        assertThat(cell.value).isEqualByComparingTo("300000")
        assertThat(template.dataGaps).isEmpty()
    }

    @Test
    fun `F01_03 uses official total-equity and equity-plus-liabilities cells`() {
        val template = F0103Mapper.map(snapshot, asOf)

        assertThat(template.templateId).isEqualTo("F01.03")
        assertThat(template.cells.single { it.rowRef == "r0300" }.value).isEqualByComparingTo(BigDecimal("200000"))
        assertThat(template.cells.single { it.rowRef == "r0310" }.value).isEqualByComparingTo(BigDecimal("500000"))
        assertThat(template.cells).allMatch { it.colRef == "c0010" }
        assertThat(template.dataGaps).isEmpty()
    }

    private fun line(code: String, accountType: String, net: String) =
        TrialBalanceLineDto(code = code, accountType = accountType, net = BigDecimal(net), currency = "CZK")
}
