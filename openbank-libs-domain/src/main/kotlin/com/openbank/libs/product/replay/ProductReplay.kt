// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.Predicate
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.ir.ProductStateTransition
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.util.SortedMap
import java.util.TreeMap

/** Debit and credit totals of one bucket. [net] is credit minus debit: for the customer deposit, money in. */
data class Movement(val debit: BigDecimal = BigDecimal.ZERO, val credit: BigDecimal = BigDecimal.ZERO) {
    val net: BigDecimal get() = credit - debit

    operator fun plus(other: Movement): Movement = Movement(debit + other.debit, credit + other.credit)

    fun sameAs(other: Movement): Boolean = debit.compareTo(other.debit) == 0 && credit.compareTo(other.credit) == 0
}

/** A posted-amount bucket: the rule that posted and the accounting class of the line. */
data class PostedKey(val ruleId: String, val accountingClass: AccountingClass) : Comparable<PostedKey> {
    override fun compareTo(other: PostedKey): Int =
        compareValuesBy(this, other, { it.ruleId }, { it.accountingClass.name })
}

/** An instruction-count bucket: the rule and the instruction kind (`POST`, `REJECT`, `SCHEDULE`, `NOTIFY`, `REPRICE`). */
data class CountKey(val ruleId: String, val kind: String) : Comparable<CountKey> {
    override fun compareTo(other: CountKey): Int = compareValuesBy(this, other, { it.ruleId }, { it.kind })
}

/** What one account did under one revision over the whole range. */
data class AccountReplay(
    val id: String,
    val currency: String,
    val finalBalance: BigDecimal,
    val finalAccruedInterest: BigDecimal,
    /** Lowest day-end balance over the range (the opening balance included), whatever moved it. */
    val minBalance: BigDecimal,
    /**
     * Lowest balance directly after an accepted customer posting (the opening balance included): what
     * a `prePosting` rule controls. Below [minBalance] only when the product's own debits (fees,
     * penalties) took the balance lower than any customer movement did.
     */
    val minCustomerBalance: BigDecimal,
    val posted: SortedMap<PostedKey, Movement>,
    val counts: SortedMap<CountKey, Int>,
    val acceptedPostings: Int,
    val rejectedPostings: Int,
) {
    /** Net customer-deposit movement the PRODUCT caused (interest, fees, penalties); customer postings excluded. */
    val outcome: BigDecimal
        get() = posted.filterKeys { it.accountingClass == AccountingClass.CUSTOMER_DEPOSIT }.values
            .fold(BigDecimal.ZERO) { acc, m -> acc + m.net }
}

/** A whole-portfolio replay under one revision. */
data class ReplayResult(
    val revision: ProductRevisionIr,
    val accounts: List<AccountReplay>,
    /** Every `evaluate` call made; bounded by days × accounts × fired hooks, so a test can assert termination. */
    val evaluations: Long,
    /** All `Post` lines of the run, per currency. Balanced iff every currency's debit equals its credit. */
    val conservation: SortedMap<String, Movement>,
) {
    val conserved: Boolean get() = conservation.values.all { it.debit.compareTo(it.credit) == 0 }
}

/**
 * Day-by-day replay of a portfolio under one revision through the one pure interpreter (ADR-0352
 * phase 4). Pure: no I/O, no clock but the [ReplayRange] calendar, no randomness. Same inputs, same
 * result, which is what lets [ProductBehaviourDiff] put two runs side by side.
 *
 * Per account, the first replayed day (the later of the range start and `openedOn`) evaluates
 * `onOpen`, whose `Schedule` instructions register the account's schedule set. Then, every day:
 * the day's customer postings in order (`prePosting`; if not rejected, its instructions, the posting
 * itself, then `postPosting`), then every registered schedule due that day in registration order.
 * `MONTHLY` falls on the last day of the month, `QUARTERLY` on the last day of a quarter, `ANNUALLY`
 * on 31 December, `AT_MATURITY` once on its date; `ON_EVENT` never fires from the calendar. Between
 * evaluations the state moves by [ProductStateTransition], the same model the golden runner uses.
 *
 * Event and balance history older than the longest predicate window either revision reads is
 * dropped as the replay advances: a predicate cannot see it, and keeping it would make a
 * multi-year run quadratic.
 */
class ProductReplay(private val interpreter: ProductInterpreter) {

    fun run(
        revision: ProductRevisionIr,
        portfolio: ReplayPortfolio,
        range: ReplayRange,
        listener: ReplayListener = ReplayListener.NONE,
        historyDays: Int = historyWindow(revision),
    ): ReplayResult {
        val runs = portfolio.accounts.map { AccountRun(revision, it) }
        var day = range.from
        while (!day.isAfter(range.to)) {
            val clock = ProductClock(day.atStartOfDay(range.zone).toInstant(), range.zone)
            for (r in runs) r.day(day, clock, historyDays, listener)
            listener.onDayEnd(day)
            day = day.plusDays(1)
        }
        val conservation = TreeMap<String, Movement>()
        for (r in runs) r.lines.forEach { (ccy, m) -> conservation.merge(ccy, m, Movement::plus) }
        return ReplayResult(revision, runs.map { it.result() }, runs.sumOf { it.evaluations }, conservation)
    }

    private data class Scheduled(val hook: Hook, val tag: String, val cadence: Cadence, val at: LocalDate?)

    private inner class AccountRun(val revision: ProductRevisionIr, val account: ReplayAccount) {
        var state: ProductState = account.initialState.copy(
            events = account.initialState.events.sortedBy { it.at },
            balanceSamples = account.initialState.balanceSamples.sortedBy { it.on },
        )
        val schedules = LinkedHashMap<Pair<Hook, String>, Scheduled>()
        val posted = TreeMap<PostedKey, Movement>()
        val counts = TreeMap<CountKey, Int>()
        val lines = TreeMap<String, Movement>()
        var evaluations = 0L
        var opened = false
        var minBalance: BigDecimal = state.balance
        var minCustomerBalance: BigDecimal = state.balance
        var accepted = 0
        var rejected = 0
        private var nextPosting = 0

        fun day(on: LocalDate, clock: ProductClock, historyDays: Int, listener: ReplayListener) {
            if (on.isBefore(state.openedOn)) return
            prune(on, historyDays)
            if (!opened) {
                opened = true
                evaluate(ProductEvent(Hook.ON_OPEN), on, clock, listener)
            }
            customerPostings(on, clock, listener)
            for (s in schedules.values.toList()) {
                if (!due(s, on)) continue
                if (s.cadence == Cadence.AT_MATURITY) schedules.remove(s.hook to s.tag)
                evaluate(ProductEvent(s.hook, tag = s.tag), on, clock, listener)
            }
            if (state.balance < minBalance) minBalance = state.balance
        }

        /** The day's customer postings in order: `prePosting`; if not rejected, the posting and `postPosting`. */
        private fun customerPostings(on: LocalDate, clock: ProductClock, listener: ReplayListener) {
            val postings = account.postings
            while (nextPosting < postings.size && postings[nextPosting].on.isBefore(on)) nextPosting++
            while (nextPosting < postings.size && postings[nextPosting].on == on) {
                val p = postings[nextPosting++].posting
                val pre = evaluate(ProductEvent(Hook.PRE_POSTING, posting = p), on, clock, listener)
                if (pre.any { it is Instruction.Reject }) {
                    rejected++
                    continue
                }
                accepted++
                state = ProductStateTransition.applyPosting(state, p, on)
                if (state.balance < minCustomerBalance) minCustomerBalance = state.balance
                listener.onCustomerPosting(account.id, on, state.currency, p)
                evaluate(ProductEvent(Hook.POST_POSTING, posting = p), on, clock, listener)
            }
        }

        private fun evaluate(
            event: ProductEvent,
            on: LocalDate,
            clock: ProductClock,
            listener: ReplayListener,
        ): List<Instruction> {
            evaluations++
            val out = interpreter.evaluate(revision, state, event, clock)
            for (i in out) record(i)
            if (out.none { it is Instruction.Reject }) {
                state = ProductStateTransition.apply(state, out, on)
                if (out.isNotEmpty()) listener.onInstructions(account.id, on, state.currency, out)
            }
            return out
        }

        private fun record(i: Instruction) {
            counts.merge(CountKey(i.ruleId, kindOf(i)), 1, Int::plus)
            when (i) {
                is Instruction.Post -> for (l in i.lines) {
                    val m = if (l.direction ==
                        PostingDirection.DEBIT
                    ) {
                        Movement(debit = l.amount)
                    } else {
                        Movement(credit = l.amount)
                    }
                    posted.merge(PostedKey(i.ruleId, l.accountingClass), m, Movement::plus)
                    lines.merge(l.currency, m, Movement::plus)
                }
                is Instruction.Schedule -> schedules[i.hook to i.tag] = Scheduled(i.hook, i.tag, i.cadence, i.at)
                is Instruction.Reject, is Instruction.Notify, is Instruction.Reprice -> Unit
            }
        }

        private fun prune(on: LocalDate, historyDays: Int) {
            val cutoff = on.minusDays(historyDays.toLong())
            val events = state.events
            val samples = state.balanceSamples
            val oldEvent = events.isNotEmpty() && events.first().at.isBefore(cutoff)
            val oldSample = samples.size > 1 && samples.first().on.isBefore(cutoff)
            if (!oldEvent && !oldSample) return
            // Keep the newest sample before the cutoff too: it is the balance the window opened with.
            val firstKept = samples.indexOfFirst { !it.on.isBefore(cutoff) }.let {
                if (it <
                    0
                ) {
                    samples.size - 1
                } else {
                    it
                }
            }
            state = state.copy(
                events = if (oldEvent) events.filter { !it.at.isBefore(cutoff) } else events,
                balanceSamples = if (oldSample) {
                    samples.subList(
                        (firstKept - 1).coerceAtLeast(0),
                        samples.size,
                    ).toList()
                } else {
                    samples
                },
            )
        }

        fun result() = AccountReplay(
            id = account.id,
            currency = state.currency,
            finalBalance = state.balance,
            finalAccruedInterest = state.accruedInterest,
            minBalance = minBalance,
            minCustomerBalance = minCustomerBalance,
            posted = posted,
            counts = counts,
            acceptedPostings = accepted,
            rejectedPostings = rejected,
        )
    }

    companion object {
        fun kindOf(i: Instruction): String = when (i) {
            is Instruction.Post -> "POST"
            is Instruction.Reject -> "REJECT"
            is Instruction.Schedule -> "SCHEDULE"
            is Instruction.Notify -> "NOTIFY"
            is Instruction.Reprice -> "REPRICE"
        }

        /** Whether a registered schedule fires on [on]. */
        private fun due(s: Scheduled, on: LocalDate): Boolean {
            val monthEnd = on.dayOfMonth == on.lengthOfMonth()
            return when (s.cadence) {
                Cadence.DAILY -> true
                Cadence.MONTHLY -> monthEnd
                Cadence.QUARTERLY -> monthEnd && on.monthValue % MONTHS_PER_QUARTER == 0
                Cadence.ANNUALLY -> monthEnd && on.month == Month.DECEMBER
                Cadence.AT_MATURITY -> s.at == on
                Cadence.ON_EVENT -> false
            }
        }

        private const val MONTHS_PER_QUARTER = 3

        /** Days of history any predicate of [revisions] can read, plus one: what the replay must keep. */
        fun historyWindow(vararg revisions: ProductRevisionIr): Int {
            val predicates = revisions.flatMap { rev ->
                listOfNotNull(rev.interest?.bonus?.predicate) +
                    rev.fees.mapNotNull { it.waiver } +
                    rev.hooks.values.flatten().mapNotNull { it.whenPredicate }
            }
            return (predicates.maxOfOrNull { windowOf(it) } ?: 0) + 1
        }

        private fun windowOf(p: Predicate): Int = when (p) {
            is Predicate.NoEvents -> p.window.days
            is Predicate.CountEvents -> p.window.days
            is Predicate.SumEvents -> p.window.days
            is Predicate.BalanceMin -> p.window.days
            is Predicate.And -> p.operands.maxOfOrNull { windowOf(it) } ?: 0
            is Predicate.Or -> p.operands.maxOfOrNull { windowOf(it) } ?: 0
            is Predicate.Not -> windowOf(p.operand)
            is Predicate.Comparison, is Predicate.PostingComparison -> 0
        }
    }
}
