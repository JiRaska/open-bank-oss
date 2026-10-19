// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.treasury.domain.LedgerMapGolden
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.glCode
import com.openbank.treasury.domain.model.AmountExpression
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingMatrix
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.Recognition
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.reference.Ifrs9Category
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ADR-0344 D7: every leg-described product settles, accrues, pays and matures through the
 * posting-rule matrix. Driven by the golden parity fixtures (their deals and ledgers), each life is
 * replayed day by day exactly as the service's daily run does it, and three things must hold:
 *
 * 1. every journal is balanced per currency (JournalSpec refuses otherwise) and posts only to
 *    accounts the ledger maps (V29/V30/V33, by class);
 * 2. after maturity every balance-sheet account the deal touched is back to zero — instrument,
 *    accrued interest, premium/discount, repo, memo pairs — so nothing is left behind;
 * 3. the nostro moved by exactly the deal's cash flows: Σ IN − Σ OUT per currency.
 */
class PostingMatrixLifecycleTest {
    private val matrixFixtures = ParityFixtures.fixtures().map { it.second }
        .filter { !ProductType.valueOf(it["product"].asText()).legacy }

    @TestFactory
    fun `every matrix product's whole life balances and leaves nothing on the balance sheet`(): List<DynamicTest> =
        matrixFixtures.flatMap { fixture ->
            val product = ProductType.valueOf(fixture["product"].asText())
            val categories = if (product in SECURITIES) Ifrs9Category.entries else listOf(Ifrs9Category.AMORTISED_COST)
            categories.map { category ->
                DynamicTest.dynamicTest("${fixture["id"].asText()} [$category]") { lifecycle(fixture, category) }
            }
        }

    private fun lifecycle(fixture: JsonNode, category: Ifrs9Category) {
        val deal = ParityFixtures.deal(fixture["deal"])
        val flows = runBlocking {
            ParityFixtures.scheduleService(fixture, deal).generate(deal, ParityFixtures.fixings(fixture)).flows
        }
            .filter { it.amount != null } // an unfixed period cannot post until its fixing (ADR-0344 D5)
        val journals = mutableListOf<JournalSpec>()
        MatrixPostings.trade(deal)?.let(journals::add)
        MatrixPostings.settlement(deal, flows, category)?.let(journals::add)
        val run = MatrixPostings.run(
            deal,
            flows,
            category,
            null,
            emptySet(),
            MatrixPostings.lastPaymentDate(deal, flows),
        )
        journals += run.map { it.spec }
        if (!deal.product.settlesOnOneDay) {
            MatrixPostings.maturity(
                deal,
                flows,
                category,
                MatrixPostings.amortisedThrough(deal, flows, MatrixPostings.lastPaymentDate(deal, flows)),
            )
                ?.let(journals::add)
        }
        assertThat(journals).describedAs("${deal.product} posted nothing").isNotEmpty()
        assertThat(journals.map { it.idempotencyKey }).doesNotHaveDuplicates()

        val balances = mutableMapOf<String, BigDecimal>()
        journals.flatMap { it.lines }.forEach { l ->
            balances.merge(l.glCode(), if (l.side == Side.DEBIT) l.amount else l.amount.negate(), BigDecimal::add)
        }
        assertThat(balances.keys).allMatch { it in LedgerMapGolden.treasury.values }
        val leftOver = balances.filter { (code, v) -> v.signum() != 0 && code !in CASH_AND_PNL }
        assertThat(leftOver).describedAs("${deal.product} left balance-sheet balances after maturity").isEmpty()

        flows.groupBy { it.currency }.forEach { (ccy, fs) ->
            val expected = fs.sumOf { f -> if (f.direction == FlowDirection.IN) f.amount!! else f.amount!!.negate() }
            val nostro = balances[LedgerMapGolden.gl("NOSTRO_ACCOUNT", "PRINCIPAL", ccy)] ?: BigDecimal.ZERO
            assertThat(nostro).describedAs("${deal.product} nostro $ccy").isEqualByComparingTo(expected)
        }
    }

    @Test
    fun `every leg-described product has rows and one declared recognition`() {
        val newProducts = ProductType.entries.filter { !it.legacy }
        newProducts.forEach { p ->
            assertThat(PostingMatrix.rules.filter { it.product == p }).describedAs("$p rules").isNotEmpty()
            assertThat(PostingMatrix.rules.filter { it.product == p }.map { it.recognition }.toSet()).hasSize(1)
        }
        assertThat(PostingMatrix.recognition(ProductType.IRS)).isEqualTo(Recognition.TRADE_DATE)
        assertThat(PostingMatrix.recognition(ProductType.BOND)).isEqualTo(Recognition.SETTLEMENT_DATE)
        listOf(
            ProductType.BOND,
            ProductType.TREASURY_BILL,
            ProductType.IRS,
            ProductType.FRA,
            ProductType.FX_FORWARD,
        ).forEach {
            assertThat(PostingMatrix.hasEvent(it, PostingEvent.REVALUATION)).describedAs("$it revalues").isTrue()
        }
        assertThat(PostingMatrix.hasEvent(ProductType.LOAN, PostingEvent.REVALUATION)).isFalse()
    }

    @Test
    fun `a revaluation posts by category - FVOCI to equity, FVTPL to P&L, never at amortised cost`() {
        val bond = ParityFixtures.deal(matrixFixtures.first { it["product"].asText() == "BOND" }["deal"])
        val day = LocalDate.of(2026, 12, 31)
        val fvoci =
            checkNotNull(
                MatrixPostings.revaluation(bond, BigDecimal("1500.00"), bond.currency, day, Ifrs9Category.FVOCI),
            )
        assertThat(fvoci.lines.map { it.glCode() to it.side }).containsExactlyInAnyOrder(
            LedgerMapGolden.gl("DEBT_SECURITY_FVOCI", "PRINCIPAL", bond.currency) to Side.DEBIT,
            LedgerMapGolden.gl("DEBT_SECURITY_FVOCI", "REVALUATION_RESERVE", bond.currency) to Side.CREDIT,
        )
        val fvtplLoss =
            checkNotNull(
                MatrixPostings.revaluation(bond, BigDecimal("-200.00"), bond.currency, day, Ifrs9Category.FVTPL),
            )
        assertThat(fvtplLoss.lines.map { it.glCode() to it.side }).containsExactlyInAnyOrder(
            LedgerMapGolden.gl("TREASURY_TRADING_RESULT", "UNREALISED_LOSS", bond.currency) to Side.DEBIT,
            LedgerMapGolden.gl("DEBT_SECURITY_FVTPL", "PRINCIPAL", bond.currency) to Side.CREDIT,
        )
        assertThat(
            MatrixPostings.revaluation(bond, BigDecimal("1500.00"), bond.currency, day, Ifrs9Category.AMORTISED_COST),
        ).isNull()
        assertThat(fvoci.idempotencyKey).isEqualTo("treasury:${bond.id}:revaluation:$day")
    }

    @Test
    fun `a payment key names the flow, an accrual key the day`() {
        val fixture = matrixFixtures.first { it["id"].asText() == "loan-czk-act365f-004" }
        val deal: Deal = ParityFixtures.deal(fixture["deal"])
        val flows: List<CashFlow> = runBlocking { ParityFixtures.scheduleService(fixture, deal).generate(deal).flows }
        val run = MatrixPostings.run(
            deal,
            flows,
            Ifrs9Category.AMORTISED_COST,
            null,
            emptySet(),
            deal.valueDate.plusDays(100),
        )
        val payment = run.first { it.spec.event == PostingEvent.PAYMENT }
        val paid = flows.first { payment.spec.idempotencyKey.endsWith(it.flowId.toString()) }
        assertThat(payment.spec.idempotencyKey).isEqualTo("treasury:${deal.id}:payment:${paid.flowId}")
        assertThat(payment.entryDate).isEqualTo(paid.paymentDate)
        assertThat(run.first { it.spec.event == PostingEvent.ACCRUAL }.spec.idempotencyKey)
            .isEqualTo("treasury:${deal.id}:accrual:${deal.valueDate.plusDays(1)}")
        // A second run from where the first stopped posts no flow twice.
        val again = MatrixPostings.run(
            deal,
            flows,
            Ifrs9Category.AMORTISED_COST,
            deal.valueDate.plusDays(100),
            run.filter {
                it.spec.event == PostingEvent.PAYMENT
            }.map { r -> flows.first { r.spec.idempotencyKey.endsWith(it.flowId.toString()) }.flowId }.toSet(),
            deal.valueDate.plusDays(100),
        )
        assertThat(again).isEmpty()
        assertThat(AmountExpression.entries).contains(AmountExpression.UNACCRUED)
    }

    private companion object {
        val SECURITIES = setOf(ProductType.BOND, ProductType.TREASURY_BILL, ProductType.BILL_OF_EXCHANGE)

        /**
         * Accounts a finished deal legitimately leaves a balance on: cash, FX position, P&L, and
         * cash in transit — the clearing account a CASH movement's other side (the receiving or
         * paying internal account) clears; the treasury posts only its own side.
         */
        val CASH_AND_PNL: Set<String> = listOf(Deal.CZK, Deal.EUR).flatMap { ccy ->
            listOf(
                "NOSTRO_ACCOUNT" to "PRINCIPAL",
                "FX_SPOT_POSITION" to "PRINCIPAL",
                "DEBT_SECURITY_AMORTISED_COST" to "INTEREST_INCOME",
                "TREASURY_LOAN" to "INTEREST_INCOME",
                "DERIVATIVE_RECEIVE_LEG" to "INTEREST_INCOME",
                "REVERSE_REPO_LENDING" to "INTEREST_INCOME",
                "DERIVATIVE_PAY_LEG" to "INTEREST_EXPENSE",
                "REPO_BORROWING" to "INTEREST_EXPENSE",
                "TREASURY_TRADING_RESULT" to "REALISED_GAIN",
                "TREASURY_TRADING_RESULT" to "REALISED_LOSS",
                "CASH_IN_TRANSIT" to "PRINCIPAL",
            ).map { (cls, role) -> LedgerMapGolden.gl(cls, role, ccy) }
        }.toSet()
    }
}
