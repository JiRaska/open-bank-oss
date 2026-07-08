// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
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

class CorepServiceTest {

    private val ledgerPort: LedgerPort = mockk()

    @Test
    fun `getTemplate dispatches C_01_00 to the own funds mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000")),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000")),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns lines
        val service = CorepService(ledgerPort)

        val template = service.getTemplate(GetCorepTemplateQuery(templateId = "C_01.00", asOf = asOf))

        assertThat(template.templateId).isEqualTo("C_01.00")
        assertThat(template.period).isEqualTo(asOf)
        assertThat(template.hasDataGaps).isTrue()
        coVerify(exactly = 1) { ledgerPort.getTrialBalance(asOf) }
    }

    @Test
    fun `getTemplate throws for an unknown or unimplemented COREP template id`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns emptyList()
        val service = CorepService(ledgerPort)

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetCorepTemplateQuery(templateId = "C_02.00", asOf = asOf)) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("C_02.00")
    }
}
