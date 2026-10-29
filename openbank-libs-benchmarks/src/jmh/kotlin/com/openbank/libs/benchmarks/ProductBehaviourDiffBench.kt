// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayPosting
import com.openbank.libs.product.replay.ReplayRange
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.SplittableRandom
import java.util.concurrent.TimeUnit

/**
 * ADR-0352 phase 4: throughput of the behavioural diff — one op replays [ACCOUNTS] savings accounts
 * for one calendar year (2028, a leap year) under two revisions (+25 bp) and renders the canonical
 * JSON. That is 2 × 1000 × 366 daily accruals plus month-end capitalisation, the loyalty-bonus
 * predicate and the customer postings.
 * The 10k-account figure in `product-ir.md` is ten times this op; the op is kept at 1000 so the
 * whole JMH run stays within its few-minute budget.
 *
 * The portfolio is built here with a seeded [SplittableRandom] rather than the
 * `SyntheticPortfolio` generator, which lives in the test-fixture library this module may not use
 * (see [ProductInterpreterBench]).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ProductBehaviourDiffBench {

    private val diff = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))
    private val revA: ProductRevisionIr = revision("0.0250")
    private val revB: ProductRevisionIr = revision("0.0275")
    private val range = ReplayRange(YEAR_START, YEAR_START.plusYears(1).minusDays(1), ZoneOffset.UTC)
    private val portfolio: ReplayPortfolio = portfolio(SplittableRandom(SEED))

    @Benchmark
    fun diffThousandAccountsYear(): Int = diff.diff(revA, revB, portfolio, range).canonicalJson.length

    private fun portfolio(random: SplittableRandom): ReplayPortfolio = ReplayPortfolio(
        (0 until ACCOUNTS).map { i ->
            val balance = BigDecimal(random.nextLong(0, MAX_BALANCE_CENTS)).movePointLeft(2)
            var day = range.from
            val postings = ArrayList<ReplayPosting>()
            while (!day.isAfter(range.to)) {
                if (day.dayOfMonth == day.lengthOfMonth()) {
                    val salary = BigDecimal(random.nextLong(SALARY_MIN_CENTS, SALARY_MAX_CENTS)).movePointLeft(2)
                    postings += ReplayPosting(day, Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, salary))
                } else if (random.nextInt(SPEND_ONE_IN) == 0) {
                    val spend = BigDecimal(random.nextLong(1, SPEND_MAX_CENTS)).movePointLeft(2)
                    postings += ReplayPosting(day, Posting(EventKind.CARD_PAYMENT, PostingDirection.DEBIT, spend))
                }
                day = day.plusDays(1)
            }
            ReplayAccount("b-$i", ProductState(currency = "EUR", balance = balance, openedOn = OPENED), postings)
        },
        SEED,
    )

    private companion object {
        const val ACCOUNTS = 1000
        const val SEED = 2028L
        const val MAX_BALANCE_CENTS = 5_000_000L
        const val SALARY_MIN_CENTS = 90_000L
        const val SALARY_MAX_CENTS = 600_000L
        const val SPEND_ONE_IN = 5
        const val SPEND_MAX_CENTS = 20_000L
        val OPENED: LocalDate = LocalDate.of(2025, 1, 1)
        val YEAR_START: LocalDate = LocalDate.of(2028, 1, 1)
        const val GOLDEN = "/product-golden/savings-bonus-predicate-event-window.json"

        /** The golden savings revision with its base rate replaced. */
        @Suppress("UNCHECKED_CAST")
        fun revision(rate: String): ProductRevisionIr {
            val text = checkNotNull(
                ProductBehaviourDiffBench::class.java.getResourceAsStream(
                    "/product-golden/savings-bonus-predicate-event-window.json",
                ),
            )
                .readBytes().toString(Charsets.UTF_8)
            val tree = (IrJson.parse(text) as Map<String, Any?>)["revision"] as Map<String, Any?>
            val interest = tree["interest"] as Map<String, Any?>
            return ProductIrParser.fromTree(tree + ("interest" to interest + ("rate" to rate)))
        }
    }
}
