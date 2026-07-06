// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinrepServiceTest {

    private val ledgerPort: LedgerPort = mockk()

    @Test
    fun `getTemplate dispatches F01_01 to the balance sheet mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000")),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000")),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns lines
        val service = FinrepService(ledgerPort)

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(template.templateId).isEqualTo("F01.01")
        assertThat(template.period).isEqualTo(asOf)
        assertThat(template.cells).anyMatch { it.rowRef == "r490" && it.value == BigDecimal("200000") }
        coVerify(exactly = 1) { ledgerPort.getTrialBalance(asOf) }
    }

    @Test
    fun `getTemplate dispatches F02_00 to the P&L mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "4000", accountType = "INCOME", net = BigDecimal("120000")),
            TrialBalanceLineDto(code = "5000", accountType = "EXPENSE", net = BigDecimal("80000")),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns lines
        val service = FinrepService(ledgerPort)

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F02.00", asOf = asOf))

        assertThat(template.templateId).isEqualTo("F02.00")
        assertThat(template.cells).anyMatch { it.rowRef == "r450" && it.value == BigDecimal("40000") }
    }

    @Test
    fun `getTemplate throws for an unknown template id`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns emptyList()
        val service = FinrepService(ledgerPort)

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetFinrepTemplateQuery(templateId = "F99.99", asOf = asOf)) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("F99.99")
    }
}
