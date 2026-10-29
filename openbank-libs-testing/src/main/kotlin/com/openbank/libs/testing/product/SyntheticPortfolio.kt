// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
import java.time.Month

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
        val onBoundary = Arb.int(1, PERCENT).bind() <= BOUNDARY_PERCENT
        val balance = if (shape.tierBoundaries.isNotEmpty() && onBoundary) {
            val edge = shape.tierBoundaries[Arb.int(0, shape.tierBoundaries.size - 1).bind()]
            edge + BigDecimal(Arb.int(-1, 1).bind()).movePointLeft(AMOUNT_SCALE)
        } else {
            balance().bind()
        }.max(BigDecimal.ZERO).setScale(AMOUNT_SCALE)
        val opensInside = Arb.int(1, PERCENT).bind() <= OPENS_INSIDE_PERCENT
        val onLeapDay = Arb.int(1, PERCENT).bind() <= LEAP_DAY_PERCENT
        val rangeDays = shape.from.until(shape.to).days.coerceAtLeast(0)
        val openedOn = if (opensInside) {
            (if (onLeapDay) leapDayIn(shape) else null)
                ?: shape.from.plusDays(Arb.int(0, rangeDays.coerceAtMost(DAYS_IN_YEAR)).bind().toLong())
        } else {
            shape.from.minusDays(Arb.int(MIN_AGE_DAYS, MAX_AGE_DAYS).bind().toLong())
        }
        val pattern = Pattern.entries[Arb.int(0, Pattern.entries.size - 1).bind()]
        val salary = Arb.bigDecimal(SALARY_MIN, SALARY_MAX).bind().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
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

    /** A balance from a skewed retail distribution: [BALANCE_BANDS] by cumulative share. */
    private fun balance(): Arb<BigDecimal> = arbitrary {
        val roll = Arb.int(1, PERCENT).bind()
        val band = BALANCE_BANDS.first { roll <= it.cumulativePercent }
        Arb.bigDecimal(band.low, band.high).bind().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    }

    private data class Band(val cumulativePercent: Int, val low: BigDecimal, val high: BigDecimal)

    private fun leapDayIn(shape: PortfolioShape): LocalDate? = (shape.from.year..shape.to.year).asSequence()
        .filter { java.time.Year.isLeap(it.toLong()) }
        .map { LocalDate.of(it, Month.FEBRUARY, LEAP_DAY) }
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
        if (random.next(SPEND_ONE_IN) != 0) return null
        val amount = BigDecimal(random.next(SPEND_MAX_CENTS) + 1).movePointLeft(AMOUNT_SCALE)
        val attempt = random.next(OVERDRAW_ATTEMPT_ONE_IN) == 0
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
        if (random.next(WITHDRAW_ONE_IN) != 0) return null
        val amount = BigDecimal(random.next(WITHDRAW_MAX_CENTS) + 1).movePointLeft(AMOUNT_SCALE)
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
        private var state = seed.toLong() and MASK

        fun next(bound: Int): Int {
            state = (state * MULTIPLIER + INCREMENT) and MASK
            return (state % bound).toInt()
        }

        private companion object {
            // The classic ANSI C constants, modulo 2^31.
            const val MULTIPLIER = 1_103_515_245L
            const val INCREMENT = 12_345L
            const val MASK = 0x7fffffffL
        }
    }

    private const val PERCENT = 100
    private const val SMALL_UP_TO = 35
    private const val MID_UP_TO = 75
    private const val UPPER_UP_TO = 95
    private const val BOUNDARY_PERCENT = 10
    private const val OPENS_INSIDE_PERCENT = 20
    private const val LEAP_DAY_PERCENT = 10
    private const val AMOUNT_SCALE = 2
    private const val DAYS_IN_YEAR = 365
    private const val MIN_AGE_DAYS = 31
    private const val MAX_AGE_DAYS = 2000
    private const val LEAP_DAY = 29
    private const val SPEND_ONE_IN = 4
    private const val SPEND_MAX_CENTS = 20_000
    private const val OVERDRAW_ATTEMPT_ONE_IN = 50
    private const val WITHDRAW_ONE_IN = 30
    private const val WITHDRAW_MAX_CENTS = 50_000
    private val SALARY_MIN = BigDecimal("900.00")
    private val SALARY_MAX = BigDecimal("6000.00")

    /** 35 % under 1 000, 40 % to 10 000, 20 % to 100 000, a 5 % tail to 2 000 000. */
    private val BALANCE_BANDS = listOf(
        Band(SMALL_UP_TO, BigDecimal("0.00"), BigDecimal("1000.00")),
        Band(MID_UP_TO, BigDecimal("1000.00"), BigDecimal("10000.00")),
        Band(UPPER_UP_TO, BigDecimal("10000.00"), BigDecimal("100000.00")),
        Band(PERCENT, BigDecimal("100000.00"), BigDecimal("2000000.00")),
    )
}
