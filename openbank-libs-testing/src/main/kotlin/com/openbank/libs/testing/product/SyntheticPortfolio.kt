// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Distribution shares, amount bands and day offsets are the portfolio's shape; naming each adds nothing.
@file:Suppress("MagicNumber")

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayPosting
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.int
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Shape of a synthetic portfolio (ADR-0352 phase 4). [tierBoundaries] are the balances a product
 * changes behaviour at (tier edges, waiver and bonus thresholds); a share of accounts is opened
 * within a cent of one of them so a backtest always exercises both sides.
 */
data class PortfolioShape(
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
    val tierBoundaries: List<BigDecimal> = emptyList(),
    /** Whether a customer may take the balance below zero; false keeps generated withdrawals within the balance. */
    val overdraftAllowed: Boolean = false,
)

/**
 * A seeded, reproducible synthetic portfolio built on [ProductArb]'s generator style: the same
 * `(seed, size, shape)` always yields the same accounts, and the seed travels inside the
 * [ReplayPortfolio] so a diff names what regenerates it.
 *
 * Balances follow a skewed retail distribution — most accounts small, a long tail of large ones —
 * plus boundary accounts a cent either side of every [PortfolioShape.tierBoundaries] value. Event
 * patterns mix month-end salary credits, card spending, occasional withdrawals and "saver" accounts
 * that never withdraw (the loyalty-bonus predicate's positive case). Some accounts open inside the
 * range, including on 29 February when the range has one.
 */
object SyntheticPortfolio {

    fun generate(seed: Long, size: Int, shape: PortfolioShape): ReplayPortfolio {
        require(size >= 0) { "size must not be negative" }
        val random = RandomSource.seeded(seed)
        val accountArb = account(shape)
        val accounts = (0 until size).map { i -> accountArb.sample(random).value("acc-%06d".format(i)) }
        return ReplayPortfolio(accounts, seed)
    }

    /** An account generator, curried on its id so the id is positional and stable. */
    private fun account(shape: PortfolioShape): Arb<(String) -> ReplayAccount> = arbitrary {
        val boundaryIndex = Arb.int(0, 9).bind()
        val balance = if (shape.tierBoundaries.isNotEmpty() && boundaryIndex == 0) {
            val edge = shape.tierBoundaries[Arb.int(0, shape.tierBoundaries.size - 1).bind()]
            edge + BigDecimal(Arb.int(-1, 1).bind()).movePointLeft(2)
        } else {
            balance().bind()
        }.max(BigDecimal.ZERO).setScale(2)
        val opensInside = Arb.int(0, 9).bind() < 2
        val rangeDays = shape.from.until(shape.to).days.coerceAtLeast(0)
        val openedOn = if (opensInside) {
            leapDayIn(shape) ?: shape.from.plusDays(Arb.int(0, rangeDays.coerceAtMost(365)).bind().toLong())
        } else {
            shape.from.minusDays(Arb.int(31, 2000).bind().toLong())
        }
        val pattern = Pattern.entries[Arb.int(0, Pattern.entries.size - 1).bind()]
        val salary = Arb.bigDecimal(
            BigDecimal("900.00"),
            BigDecimal("6000.00"),
        ).bind().setScale(2, RoundingMode.HALF_UP)
        val postingSeed = Arb.int(0, Int.MAX_VALUE).bind()
        val build: (String) -> ReplayAccount = { id ->
            val start = if (openedOn.isAfter(shape.from)) openedOn else shape.from
            ReplayAccount(
                id,
                ProductState(currency = shape.currency, balance = balance, openedOn = openedOn),
                postings(pattern, balance, salary, start, shape, postingSeed),
            )
        }
        build
    }

    private fun balance(): Arb<BigDecimal> = arbitrary {
        when (Arb.int(0, 99).bind()) {
            in 0..34 -> Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("1000.00")).bind()
            in 35..74 -> Arb.bigDecimal(BigDecimal("1000.00"), BigDecimal("10000.00")).bind()
            in 75..94 -> Arb.bigDecimal(BigDecimal("10000.00"), BigDecimal("100000.00")).bind()
            else -> Arb.bigDecimal(BigDecimal("100000.00"), BigDecimal("2000000.00")).bind()
        }.setScale(2, RoundingMode.HALF_UP)
    }

    private fun leapDayIn(shape: PortfolioShape): LocalDate? = (shape.from.year..shape.to.year).asSequence()
        .filter { java.time.Year.isLeap(it.toLong()) }
        .map { LocalDate.of(it, 2, 29) }
        .firstOrNull { !it.isBefore(shape.from) && !it.isAfter(shape.to) }

    /** Event behaviour classes: who earns, who spends, who saves. */
    private enum class Pattern { SAVER, SALARIED, SPENDER, DORMANT }

    /**
     * The account's customer movements, drawn from an [Lcg] seeded by the generator. Withdrawals
     * stay within the running balance unless [PortfolioShape.overdraftAllowed]; a few deliberately
     * do not, so the product's `prePosting` rejection is exercised.
     */
    private fun postings(
        pattern: Pattern,
        opening: BigDecimal,
        salary: BigDecimal,
        start: LocalDate,
        shape: PortfolioShape,
        seed: Int,
    ): List<ReplayPosting> {
        if (pattern == Pattern.DORMANT) return emptyList()
        val random = Lcg(seed)
        val out = ArrayList<ReplayPosting>()
        var running = opening
        var day = start
        while (!day.isAfter(shape.to)) {
            // Month-end credit for every active pattern: the month-end boundary is where capitalisation
            // and fees fire, so the replay always sees a posting and a schedule on the same day.
            if (day.dayOfMonth == day.lengthOfMonth()) {
                out += ReplayPosting(day, Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, salary))
                running += salary
            }
            val debit = when (pattern) {
                Pattern.SPENDER -> spend(random, running, shape.overdraftAllowed)
                Pattern.SALARIED -> withdraw(random, running, shape.overdraftAllowed)
                Pattern.SAVER, Pattern.DORMANT -> null
            }
            if (debit != null) {
                out += ReplayPosting(day, debit)
                if (debit.amount <= running || shape.overdraftAllowed) running -= debit.amount
            }
            day = day.plusDays(1)
        }
        return out
    }

    /** One day in four a card payment; one in fifty of those deliberately more than the balance holds. */
    private fun spend(random: Lcg, running: BigDecimal, overdraft: Boolean): Posting? {
        if (random.next(4) != 0) return null
        val amount = BigDecimal(random.next(20_000) + 1).movePointLeft(2)
        val attempt = random.next(50) == 0
        return if (attempt ||
            overdraft ||
            amount <= running
        ) {
            Posting(EventKind.CARD_PAYMENT, PostingDirection.DEBIT, amount)
        } else {
            null
        }
    }

    /** About one day in thirty a cash withdrawal, within the balance unless overdraft is allowed. */
    private fun withdraw(random: Lcg, running: BigDecimal, overdraft: Boolean): Posting? {
        if (random.next(30) != 0) return null
        val amount = BigDecimal(random.next(50_000) + 1).movePointLeft(2)
        return if (overdraft ||
            amount <= running
        ) {
            Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, amount)
        } else {
            null
        }
    }

    /** A linear-congruential sequence: cheap for a multi-year stream and a pure function of its seed. */
    private class Lcg(seed: Int) {
        private var state = seed.toLong() and 0x7fffffffL

        fun next(bound: Int): Int {
            state = (state * 1_103_515_245L + 12_345L) and 0x7fffffffL
            return (state % bound).toInt()
        }
    }
}
