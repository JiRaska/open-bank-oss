// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
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
            TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
            TrialBalanceLineDto(
                code = "2000",
                accountType = "LIABILITY",
                net = BigDecimal("-300000"),
                currency = "CZK",
            ),
            TrialBalanceLineDto(code = "4100", accountType = "INCOME", net = BigDecimal("-260000"), currency = "CZK"),
            TrialBalanceLineDto(code = "5100", accountType = "EXPENSE", net = BigDecimal("60000"), currency = "CZK"),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(lines, ledgerSays = true)
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(template.templateId).isEqualTo("F01.01")
        assertThat(template.period).isEqualTo(asOf)
        assertThat(template.cells.single { it.rowRef == "r0380" }.value).isEqualByComparingTo("500000")
        coVerify(exactly = 1) { ledgerPort.getTrialBalance(asOf) }
    }

    @Test
    fun `getTemplate dispatches F02_00 to the P&L mapper`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto(code = "4100", accountType = "INCOME", net = BigDecimal("-120000"), currency = "CZK"),
            TrialBalanceLineDto(code = "5100", accountType = "EXPENSE", net = BigDecimal("80000"), currency = "CZK"),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(lines, ledgerSays = true)
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F02.00", asOf = asOf))

        assertThat(template.templateId).isEqualTo("F02.00")
        assertThat(template.cells).anyMatch { it.rowRef == "r0670" && it.value == BigDecimal("40000") }
    }

    @Test
    fun `getTemplate dispatches F01_02 and F01_03 with official coordinates and signs`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        val lines = listOf(
            TrialBalanceLineDto("1000", "ASSET", BigDecimal("500000"), "CZK"),
            TrialBalanceLineDto("2000", "LIABILITY", BigDecimal("-300000"), "CZK"),
            TrialBalanceLineDto("4100", "INCOME", BigDecimal("-260000"), "CZK"),
            TrialBalanceLineDto("5100", "EXPENSE", BigDecimal("60000"), "CZK"),
        )
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(lines, ledgerSays = true)
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val liabilities = service.getTemplate(GetFinrepTemplateQuery("F01.02", asOf))
        val equity = service.getTemplate(GetFinrepTemplateQuery("F01.03", asOf))

        assertThat(liabilities.cells.single { it.rowRef == "r0300" }.value).isEqualByComparingTo("300000")
        assertThat(equity.cells.single { it.rowRef == "r0300" }.value).isEqualByComparingTo("200000")
        assertThat(equity.cells.single { it.rowRef == "r0310" }.value).isEqualByComparingTo("500000")
        assertThat(registry.get("openbank.finrep.templates.rendered").tag("template", "F01.02").counter().count())
            .isEqualTo(1.0)
        assertThat(registry.get("openbank.finrep.templates.rendered").tag("template", "F01.03").counter().count())
            .isEqualTo(1.0)
    }

    @Test
    fun `getTemplate throws for an unknown template id`(): Unit = runBlocking {
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
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
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(
            listOf(
                TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
                TrialBalanceLineDto(
                    code = "2000",
                    accountType = "LIABILITY",
                    net = BigDecimal("-300000"),
                    currency = "CZK",
                ),
                TrialBalanceLineDto(
                    code = "4000",
                    accountType = "INCOME",
                    net = BigDecimal("-260000"),
                    currency = "CZK",
                ),
                TrialBalanceLineDto(
                    code = "5000",
                    accountType = "EXPENSE",
                    net = BigDecimal("60000"),
                    currency = "CZK",
                ),
            ),
            ledgerSays = true,
        )
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("service", "finrep").tag("framework", "finrep").tag("template", "F01.01")
                .tag("balanced", "true")
                .tag("balance_verdict", "agreed_balanced")
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.finrep.template.render.duration").tag("template", "F01.01").timer().count(),
        ).isEqualTo(1L)
        assertThat(
            registry.get("openbank.finrep.trial_balance.lines").tag("template", "F01.01").summary().totalAmount(),
        ).isEqualTo(4.0)
        assertThat(
            registry.get("openbank.finrep.template.cells").tag("template", "F01.01").summary().totalAmount(),
        ).isEqualTo(template.cells.size.toDouble())
    }

    @Test
    fun `the balanced tag takes the value FALSE for a trial balance that does not tie out`(): Unit = runBlocking {
        // The falsification half of the test above, and the reason issue #5987 was filed: while both
        // mappers passed a hardcoded `true`, this series had exactly ONE reachable value, so an alert
        // built on it could never fire and the tag looked like health monitoring while measuring
        // nothing. Asserting `balanced=false` is reachable is what makes the tag worth alerting on.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(
            listOf(
                TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
                TrialBalanceLineDto(
                    code = "2000",
                    accountType = "LIABILITY",
                    net = BigDecimal("-410000"),
                    currency = "CZK",
                ),
            ),
            ledgerSays = false,
        )
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(template.isBalanced).isFalse()
        assertThat(template.balanceVerdict).isEqualTo(BalanceVerdict.AGREED_IMBALANCED)
        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("framework", "finrep").tag("template", "F01.01").tag("balanced", "false")
                .tag("balance_verdict", "agreed_imbalanced")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a TRUNCATED response that still ties out is blocked and reported as a disagreement`(): Unit = runBlocking {
        // Issue #6011, and the case neither source can catch alone. Ledger sealed a trial balance
        // that does NOT tie out and said so (`balanced = false`); the lines that reached finrep are
        // a subset that happens to sum to zero per currency — a capped or paginated `lines` array,
        // a filtering proxy, a partial deserialisation. finrep's own recomputation (#6010) is
        // SATISFIED here, so on its own it renders a well-formed 200 of under-reported figures, and
        // `trial_balance.lines` shows no collapse either, because lines did arrive.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(
            listOf(
                TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
                TrialBalanceLineDto(
                    code = "2000",
                    accountType = "LIABILITY",
                    net = BigDecimal("-500000"),
                    currency = "CZK",
                ),
            ),
            ledgerSays = false,
        )
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(template.balanceVerdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
        assertThat(template.isBalanced).isFalse()
        // The disagreement gets its OWN series value: an operator has to be able to tell "the books
        // do not balance" (ledger's problem) from "the evidence I was given is not the evidence
        // ledger sealed" (transport's problem). One boolean cannot say which.
        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("framework", "finrep").tag("template", "F01.01").tag("balanced", "false")
                .tag("balance_verdict", "sources_disagree")
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.finrep.trial_balance.lines").tag("template", "F01.01").summary().totalAmount(),
        ).describedAs("the line-count detector sees nothing wrong — lines DID arrive").isEqualTo(2.0)
    }

    @Test
    fun `a ledger response carrying NO verdict is blocked, and not as an imbalance`(): Unit = runBlocking {
        // Absence is a third state. Ledger declares `balanced` unconditionally and the committed
        // pact pins it, so a response without one is a contract change — which must block a
        // regulatory submission, but must never be reported as the bank's books failing to balance.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(
            listOf(
                TrialBalanceLineDto(code = "1000", accountType = "ASSET", net = BigDecimal("500000"), currency = "CZK"),
                TrialBalanceLineDto(
                    code = "2000",
                    accountType = "LIABILITY",
                    net = BigDecimal("-500000"),
                    currency = "CZK",
                ),
            ),
            ledgerSays = null,
        )
        val service = FinrepService(ledgerPort, FinrepMetricsAdapter(registry))

        val template = service.getTemplate(GetFinrepTemplateQuery(templateId = "F01.01", asOf = asOf))

        assertThat(template.balanceVerdict).isEqualTo(BalanceVerdict.LEDGER_FLAG_ABSENT)
        assertThat(template.isBalanced).isFalse()
        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("template", "F01.01").tag("balance_verdict", "ledger_flag_absent")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `an empty trial balance is still sampled, because a report of zeros still returns 200`(): Unit = runBlocking {
        // This is the under-reporting detector: a truncated ledger response renders a well-formed
        // template of honest-looking zeros, and `trial_balance.lines` is the only series that shows it.
        val asOf = LocalDate.of(2026, 6, 30)
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
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
        coEvery { ledgerPort.getTrialBalance(asOf) } returns snapshot(emptyList(), ledgerSays = true)
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

    /**
     * The ledger verdict is passed EXPLICITLY at every call site, never derived from the lines
     * (issue #6011). Deriving it would make both sides of the cross-check move together, and every
     * disagreement case in this file would become structurally unreachable — the vacuity #6010
     * removed one level down, reintroduced in the test instead of the production code.
     */
    private fun snapshot(lines: List<TrialBalanceLineDto>, ledgerSays: Boolean?) =
        TrialBalanceSnapshot(lines = lines, ledgerReportsBalanced = ledgerSays)
}
