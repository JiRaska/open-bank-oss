// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
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

class FinrepServiceTest {

    private val ledgerPort: LedgerPort = mockk()

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // instrumentation assertions below fail if the use case stops emitting.
    private val registry = SimpleMeterRegistry()

    @Test
    fun `getTemplate dispatches F01_01 to the balance sheet mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000")),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000")),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns lines
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

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
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F02.00", asOf = asOf))

        assertThat(template.templateId).isEqualTo("F02.00")
        assertThat(template.cells).anyMatch { it.rowRef == "r450" && it.value == BigDecimal("40000") }
    }

    @Test
    fun `getTemplate throws for an unknown template id`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns emptyList()
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetFinrepTemplateQuery(templateId = "F99.99", asOf = asOf)) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("F99.99")
    }

    @Test
    fun `a rendered template publishes its input size, cell count and balanced flag`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns listOf(
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000")),
            TrialBalanceLineDto(code = "2000", accountType = "LIABILITY", net = BigDecimal("300000")),
        )
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("service", "finrep").tag("framework", "finrep").tag("template", "F01.01")
                .tag("balanced", template.isBalanced.toString())
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.finrep.template.render.duration").tag("template", "F01.01").timer().count(),
        ).isEqualTo(1L)
        assertThat(
            registry.get("openbank.finrep.trial_balance.lines").tag("template", "F01.01").summary().totalAmount(),
        ).isEqualTo(2.0)
        assertThat(
            registry.get("openbank.finrep.template.cells").tag("template", "F01.01").summary().totalAmount(),
        ).isEqualTo(template.cells.size.toDouble())
    }

    @Test
    fun `an empty trial balance is still sampled, because a report of zeros still returns 200`(): Unit = runBlocking {
        // This is the under-reporting detector: a truncated ledger response renders a well-formed
        // template of honest-looking zeros, and `trial_balance.lines` is the only series that shows it.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns emptyList()
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        val lines = registry.get("openbank.finrep.trial_balance.lines").tag("template", "F01.01").summary()
        assertThat(lines.count()).isEqualTo(1L)
        assertThat(lines.totalAmount()).isEqualTo(0.0)
    }

    @Test
    fun `an unknown template is counted WITHOUT its caller-supplied id as a tag`(): Unit = runBlocking {
        // Cardinality contract: the template id is a path parameter, so tagging the failure with it
        // would let any client mint unbounded series.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns emptyList()
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetFinrepTemplateQuery(templateId = "F99.99", asOf = asOf)) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        val failures = registry.get("openbank.finrep.template.failures")
            .tag("framework", "finrep").tag("reason", "unknown_template").counter()
        assertThat(failures.count()).isEqualTo(1.0)
        assertThat(failures.id.tags.map { it.key }).doesNotContain("template")
    }

    @Test
    fun `a ledger outage is counted as ledger_unavailable and still rethrown`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } throws RuntimeException("ledger-service unreachable")
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        assertThatThrownBy {
            runBlocking { service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf)) }
        }.isInstanceOf(RuntimeException::class.java)

        assertThat(
            registry.get("openbank.finrep.template.failures")
                .tag("framework", "finrep").tag("reason", "ledger_unavailable").counter().count(),
        ).isEqualTo(1.0)
        // No render happened, so nothing must claim one did.
        assertThat(registry.find("openbank.finrep.templates.rendered").counters()).isEmpty()
    }
}
