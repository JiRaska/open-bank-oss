// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.treasury.application.port.out.AnalysisFlow
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.usecase.CashFlowAnalysisRequest
import com.openbank.treasury.application.usecase.CashFlowAnalysisService
import com.openbank.treasury.application.usecase.FlowExpression
import com.openbank.treasury.application.usecase.FlowScenario
import com.openbank.treasury.application.usecase.ScenarioNotAvailableException
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.ObservationSource
import com.openbank.treasury.domain.marketdata.ObservationStatus
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.reference.BookPayload
import com.openbank.treasury.domain.reference.BookPurpose
import com.openbank.treasury.domain.reference.Ifrs9Category
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CashFlowAnalysisServiceTest {
    private val day = LocalDate.parse("2026-11-02")
    private val flows = mockk<CashFlowRepository>()
    private val market = mockk<MarketDataRepository>()
    private val refs = mockk<ReferenceRepository>()
    private val service = CashFlowAnalysisService(flows, market, refs)

    private fun flow(
        date: LocalDate,
        amount: String?,
        dir: FlowDirection,
        ccy: String = "CZK",
        book: String? = "B-ROOT",
        kind: DealKind = DealKind.ACTUAL,
        account: String? = "ACC-1",
    ) = AnalysisFlow(
        CashFlow(
            UUID.randomUUID(), UUID.randomUUID(), 1, 0, 0, CashFlowKind.INTEREST, dir, ccy, null, null, null, date,
            null, null, RateSource.FIXED, amount?.let(::BigDecimal), CashFlowState.PROJECTED, "h",
        ),
        kind,
        book,
        account,
    )

    private fun req(
        expression: FlowExpression = FlowExpression.NET,
        convert: Boolean = false,
        monthly: Boolean = false,
        book: String? = null,
        account: String? = null,
        scenario: FlowScenario = FlowScenario.CONTRACTUAL,
    ) = CashFlowAnalysisRequest(day, "CZK", convert, expression, scenario, monthly, book, account)

    private fun rows(vararg f: AnalysisFlow) {
        coEvery { flows.analysisFlows(any(), any(), any(), any()) } returns f.toList()
    }

    private fun book(id: String, parent: String?) = ReferenceRecord(
        ReferenceKind.BOOK, id, 1, ReferenceStatus.ACTIVE, day.minusYears(1), null, true,
        BookPayload(id, parent, id, BookPurpose.BANKING_BOOK, Ifrs9Category.AMORTISED_COST),
        DealFixtures.dealer, DealFixtures.NOW, "test",
    )

    @Test
    fun `behavioural and stress are typed refusals, never a contractual answer under another name`() {
        listOf(FlowScenario.BEHAVIOURAL, FlowScenario.STRESS).forEach { s ->
            assertThatThrownBy { runBlocking { service.analyse(req(scenario = s)) } }
                .isInstanceOf(ScenarioNotAvailableException::class.java)
        }
    }

    @Test
    fun `gross, net and cumulative per day and per month`() = runBlocking<Unit> {
        rows(
            flow(day.plusDays(1), "100.00", FlowDirection.IN),
            flow(day.plusDays(1), "40.00", FlowDirection.OUT),
            flow(day.plusDays(40), "30.00", FlowDirection.OUT),
        )
        val net = service.analyse(req()).series.single().buckets
        assertThat(net.map { it.value }).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("60.00"), BigDecimal("-30.00"))
        val gross = service.analyse(req(FlowExpression.GROSS)).series.single().buckets
        assertThat(gross.first().value).isEqualByComparingTo("140.00")
        val cum = service.analyse(req(FlowExpression.CUMULATIVE)).series.single().buckets
        assertThat(cum.last().value).isEqualByComparingTo("30.00")
        val monthly = service.analyse(req(monthly = true)).series.single().buckets
        assertThat(monthly.map { it.date }).containsExactly(day.withDayOfMonth(1), day.plusDays(40).withDayOfMonth(1))
    }

    @Test
    fun `conversion uses the approved fixing of the date and a missing one is a gap, never a guessed rate`() =
        runBlocking<Unit> {
            rows(flow(day, "10.00", FlowDirection.IN, "EUR"), flow(day, "5.00", FlowDirection.IN, "USD"))
            coEvery { market.active("FX_EUR_CZK", day, day) } returns listOf(
                IndexObservation(
                    "FX_EUR_CZK",
                    day,
                    BigDecimal("25.000"),
                    ObservationSource.IMPORT,
                    UUID.randomUUID(),
                    ObservationStatus.ACTIVE,
                ),
            )
            coEvery { market.active("FX_USD_CZK", day, day) } returns emptyList()
            val a = service.analyse(req(convert = true))
            assertThat(a.reference).isNull()
            assertThat(a.dataGaps.single().reason).contains("FX_USD_CZK")
            assertThat(a.flows.first { it.currency == "EUR" }.referenceAmount).isEqualByComparingTo("250.00")
            assertThat(a.flows.first { it.currency == "USD" }.referenceAmount).isNull()

            rows(flow(day, "10.00", FlowDirection.IN, "EUR"), flow(day, "3.00", FlowDirection.OUT))
            val b = service.analyse(req(convert = true))
            assertThat(b.reference!!.buckets.single().net).isEqualByComparingTo("247.00")
        }

    @Test
    fun `a flow without an amount is a gap, not a zero`() = runBlocking<Unit> {
        rows(flow(day, null, FlowDirection.IN), flow(day, "1.00", FlowDirection.IN))
        val a = service.analyse(req())
        assertThat(a.flows).hasSize(1)
        assertThat(a.dataGaps.single().reason).contains("no amount")
    }

    @Test
    fun `the book filter takes descendants and the account filter the cash account`() = runBlocking<Unit> {
        coEvery { refs.list(ReferenceKind.BOOK, ReferenceStatus.ACTIVE, day) } returns
            listOf(book("B-ROOT", null), book("B-CHILD", "B-ROOT"), book("B-GRAND", "B-CHILD"), book("B-OTHER", null))
        rows(
            flow(day, "1.00", FlowDirection.IN, book = "B-GRAND"),
            flow(day, "2.00", FlowDirection.IN, book = "B-OTHER"),
            flow(day, "4.00", FlowDirection.IN, book = "B-CHILD", account = "ACC-2"),
        )
        assertThat(service.analyse(req(book = "B-CHILD")).flows.map { it.amount })
            .usingElementComparator(
                BigDecimal::compareTo,
            ).containsExactlyInAnyOrder(BigDecimal("1.00"), BigDecimal("4.00"))
        assertThat(
            service.analyse(req(book = "B-ROOT", account = "ACC-1")).flows.single().amount,
        ).isEqualByComparingTo("1.00")
    }

    @Test
    fun `the horizon and identifiers are bounded`() {
        assertThatThrownBy {
            CashFlowAnalysisRequest(day, "CZK", horizonDays = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CashFlowAnalysisRequest(day, "CZK", horizonDays = 3_661)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CashFlowAnalysisRequest(day, "czk") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
