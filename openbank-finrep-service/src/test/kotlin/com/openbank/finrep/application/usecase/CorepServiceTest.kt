// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
import com.openbank.finrep.application.port.inbound.TrialBalanceEvidence
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.infrastructure.observability.FinrepMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // instrumentation assertions below fail if the use case stops emitting.
    private val registry = SimpleMeterRegistry()

    @Test
    fun `live working preview never reads frozen evidence implicitly`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 7, 31)
        coEvery { ledgerPort.getLiveTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        service.getTemplate(GetCorepTemplateQuery("C_01.00", asOf, TrialBalanceEvidence.LIVE_PREVIEW))

        coVerify(exactly = 1) { ledgerPort.getLiveTrialBalance(asOf) }
        coVerify(exactly = 0) { ledgerPort.getTrialBalance(any()) }
    }

    @Test
    fun `getTemplate dispatches C_01_00 to the own funds mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000"), currency = "CZK"),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(lines, ledgerSays = true)
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetCorepTemplateQuery(templateId = "C_01.00", asOf = asOf))

        assertThat(template.templateId).isEqualTo("C_01.00")
        assertThat(template.period).isEqualTo(asOf)
        assertThat(template.hasDataGaps).isTrue()
        coVerify(exactly = 1) { ledgerPort.getTrialBalance(asOf) }
    }

    @Test
    fun `getTemplate throws for an unknown or unimplemented COREP template id`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetCorepTemplateQuery(templateId = "C_02.00", asOf = asOf)) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("C_02.00")
    }

    @Test
    fun `a rendered COREP template publishes its flagged data-gap cell count`(): Unit = runBlocking {
        // ADR-0097 forbids silent gaps, so C 01.00's capital-structure rows ship as explicit flagged
        // zeros. That honesty is invisible without a series counting them.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(
            listOf(
                TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
            ),
            ledgerSays = true,
        )
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetCorepTemplateQuery(templateId = "C_01.00", asOf = asOf))
        val expectedGaps = template.cells.count { it.isDataGap }

        assertThat(expectedGaps).isGreaterThan(0)
        assertThat(
            registry.get("openbank.finrep.template.data_gap_cells")
                .tag("framework", "corep").tag("template", "C_01.00").summary().totalAmount(),
        ).isEqualTo(expectedGaps.toDouble())
    }

    @Test
    fun `COREP is tagged balanced=not_applicable rather than pretending it balanced`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        service.getTemplate(GetCorepTemplateQuery(templateId = "C_01.00", asOf = asOf))

        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("service", "finrep").tag("framework", "corep").tag("template", "C_01.00")
                .tag("balanced", "not_applicable")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `an unimplemented COREP template is counted as a framework-tagged failure`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
        val service = CorepService(ledgerPort, FinrepMetricsAdapter(registry))

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetCorepTemplateQuery(templateId = "C_02.00", asOf = asOf)) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            registry.get("openbank.finrep.template.failures")
                .tag("framework", "corep").tag("reason", "unknown_template").counter().count(),
        ).isEqualTo(1.0)
    }

    /**
     * The ledger verdict is passed EXPLICITLY at every call site, never derived from the lines
     * (issue #6011). Deriving it would make both sides of the cross-check move together, and every
     * disagreement case in this file would become structurally unreachable — the vacuity #6010
     * removed one level down, reintroduced in the test instead of the production code.
     */
    private fun snapshot(lines: List<TrialBalanceLineDto>, ledgerSays: Boolean?) =
        TrialBalanceSnapshot(lines = lines, ledgerReportsBalanced = ledgerSays)
}
