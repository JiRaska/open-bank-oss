// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.libs.testing.product.PortfolioShape
import com.openbank.libs.testing.product.SyntheticPortfolio
import com.openbank.products.catalog.ReferenceProducts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-0352 §11.4 revision-vs-revision backtest: the reference savings product against a second
 * revision with the base rate raised by 25 bp, through the production arithmetic. The expected
 * delta is computed here from first principles, not by the library under test.
 */
class ProductBacktestTest {

    private val diff = ProductBehaviourDiff(ProductReplayScenario.interpreter)
    private val revA: ProductRevisionIr = ProductReplayScenario.loadSigned("savings-fixed")
    private val revB: ProductRevisionIr = plus25bp()

    private fun plus25bp(): ProductRevisionIr {
        val tree = ReferenceProducts.savingsFixed.tree

        @Suppress("UNCHECKED_CAST")
        val interest = tree["interest"] as Map<String, Any?>
        val rate = BigDecimal(interest["rate"].toString()) + BigDecimal("0.0025")
        return ProductIrParser.fromTree(tree + ("interest" to interest + ("rate" to rate.toPlainString())))
    }

    @Test
    fun `a 25 bp rise reports exactly the independently computed aggregate accrual delta`() {
        // 30 January days: no month end, so no capitalisation compounds the difference and no fee
        // or bonus decision can move. The expectation is the accrual contract written out by hand
        // (RoundingPolicy.INTEREST_DAILY_RATE then INTEREST_ACCRUAL: the ACT/365 daily rate rounded
        // HALF_UP to 10 places, then balance × daily rate rounded HALF_UP to 6), not the library.
        val balances = listOf("3650.00", "7300.00", "36500.00", "730000.00", "1234.56").map(::BigDecimal)
        val portfolio = ReplayPortfolio(
            balances.mapIndexed { i, b ->
                ReplayAccount(
                    "bt-$i",
                    ProductState(currency = "EUR", balance = b, openedOn = LocalDate.of(2025, 6, 1)),
                    emptyList(),
                )
            },
        )
        val range = ReplayRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 30), ZoneOffset.UTC)
        val rate = BigDecimal("0.0250")
        fun accrual(b: BigDecimal, r: BigDecimal) =
            b.multiply(r.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)).setScale(6, RoundingMode.HALF_UP)
        val expected = balances.fold(BigDecimal.ZERO) { acc, b ->
            acc + (accrual(b, rate + BigDecimal("0.0025")) - accrual(b, rate)) * BigDecimal(range.days)
        }

        val d = diff.diff(revA, revB, portfolio, range)

        @Suppress("UNCHECKED_CAST")
        val rows = (d.tree["aggregate"] as Map<String, Any?>)["posted"] as List<Map<String, Any?>>
        assertThat(rows.map { "${it["ruleId"]}/${it["accountingClass"]}" })
            .containsExactly("interest.base/ACCRUED_INTEREST_PAYABLE", "interest.base/INTEREST_EXPENSE")
        val reported = BigDecimal(rows.first()["deltaNet"] as String)
        println("BACKTEST expected=${expected.toPlainString()} reported=${reported.toPlainString()}")
        assertThat(reported).isEqualByComparingTo(expected)
        assertThat(BigDecimal(rows.last()["deltaNet"] as String)).isEqualByComparingTo(expected.negate())
        assertThat(d.tree["changedAccounts"]).isEqualTo(balances.size)
    }

    @Test
    fun `identical revisions give an empty diff over a generated multi-year portfolio`() {
        val (portfolio, range) = generated()
        val d = diff.diff(revA, ProductReplayScenario.loadSigned("savings-fixed"), portfolio, range)
        assertThat(d.identical).isTrue()
        assertThat(d.tree["changedAccounts"]).isEqualTo(0)
        assertThat(d.tree["accounts"] as List<*>).isEmpty()
    }

    @Test
    fun `the diff is byte-identical across runs and raises the base accrual for the portfolio`() {
        val (portfolio, range) = generated()
        val first = diff.diff(revA, revB, portfolio, range)
        val again = ProductBehaviourDiff(ProductReplayScenario.interpreter)
            .diff(revA, revB, generated().first, range).canonicalJson
        val h1 = AuditChain.sha256Hex(first.canonicalJson)
        val h2 = AuditChain.sha256Hex(again)
        println("DETERMINISM sha256 run1=$h1 run2=$h2 bytes=${again.length}")
        assertThat(h2).isEqualTo(h1)

        @Suppress("UNCHECKED_CAST")
        val rows = (first.tree["aggregate"] as Map<String, Any?>)["posted"] as List<Map<String, Any?>>
        val base = rows.single {
            it["ruleId"] == "interest.base" && it["accountingClass"] == "ACCRUED_INTEREST_PAYABLE"
        }
        assertThat(BigDecimal(base["deltaNet"] as String)).isPositive()
        assertThat((first.tree["portfolio"] as Map<*, *>)["seed"]).isEqualTo("2027")
        @Suppress("UNCHECKED_CAST")
        val conservation = first.tree["conservation"] as Map<String, Map<String, Any?>>
        assertThat(conservation.values).allMatch { it["conserved"] == true }
    }

    private fun generated(): Pair<ReplayPortfolio, ReplayRange> {
        val range = ReplayRange(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 12, 31), ZoneOffset.UTC)
        val shape = PortfolioShape("EUR", range.from, range.to, ProductReplayScenarioTest.thresholds(revA))
        return SyntheticPortfolio.generate(2027L, 150, shape) to range
    }
}
