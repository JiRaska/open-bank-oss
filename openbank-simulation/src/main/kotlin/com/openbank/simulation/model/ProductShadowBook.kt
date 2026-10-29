// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.replay.ReplayListener
import com.openbank.libs.product.replay.ReplayPortfolio
import java.math.BigDecimal
import java.time.LocalDate
import java.util.TreeMap

/**
 * The ADR-0352 phase-4 shadow ledger: an in-memory double-entry book that receives every
 * instruction a [com.openbank.libs.product.replay.ProductReplay] produces, plus each accepted
 * customer posting, and proves the trial balance is zero at every simulated day end.
 *
 * Deliberately not [LedgerState]: that book holds real `JournalEntry` aggregates whose `Money`
 * is at the currency's minor-unit scale, while an IR accrual line carries the interest-accrual
 * scale (6 places) — booking it there would round the very amounts this book exists to reconcile.
 * Positions here are exact `BigDecimal`s, keyed by accounting class and, for the two per-customer
 * classes (deposit and accrued interest payable), by account; every other class is one GL line.
 *
 * A customer posting has no counter-line in the IR (it is the movement the hook was asked about),
 * so the book gives it one on [AccountingClass.SETTLEMENT], the clearing side of a real transfer.
 * Opening balances are booked against the same line by [open].
 */
class ProductShadowBook(private val dropLineEvery: Int = 0) : ReplayListener {

    /** A ledger position: accounting class, the customer account for per-customer classes, currency. */
    data class Position(val accountingClass: AccountingClass, val account: String, val currency: String) :
        Comparable<Position> {
        override fun compareTo(other: Position): Int = compareValuesBy(
            this,
            other,
            { it.accountingClass.name },
            { it.account },
            { it.currency },
        )
    }

    private val positions = TreeMap<Position, BigDecimal>()
    private val net = TreeMap<String, BigDecimal>()
    private val violations = ArrayList<String>()
    private var lines = 0L
    var daysChecked = 0
        private set

    /** Books every account's opening deposit balance against settlement, so positions start where the replay does. */
    fun open(portfolio: ReplayPortfolio) {
        for (a in portfolio.accounts) {
            val s = a.initialState
            book(AccountingClass.CUSTOMER_DEPOSIT, a.id, s.currency, PostingDirection.CREDIT, s.balance)
            book(AccountingClass.SETTLEMENT, a.id, s.currency, PostingDirection.DEBIT, s.balance)
            book(AccountingClass.ACCRUED_INTEREST_PAYABLE, a.id, s.currency, PostingDirection.CREDIT, s.accruedInterest)
            book(AccountingClass.INTEREST_EXPENSE, a.id, s.currency, PostingDirection.DEBIT, s.accruedInterest)
        }
    }

    override fun onInstructions(accountId: String, on: LocalDate, currency: String, instructions: List<Instruction>) {
        for (post in instructions.filterIsInstance<Instruction.Post>()) {
            for (l in post.lines) book(l.accountingClass, accountId, l.currency, l.direction, l.amount)
        }
    }

    override fun onCustomerPosting(accountId: String, on: LocalDate, currency: String, posting: Posting) {
        val counter = if (posting.direction ==
            PostingDirection.CREDIT
        ) {
            PostingDirection.DEBIT
        } else {
            PostingDirection.CREDIT
        }
        book(AccountingClass.CUSTOMER_DEPOSIT, accountId, currency, posting.direction, posting.amount)
        book(AccountingClass.SETTLEMENT, accountId, currency, counter, posting.amount)
    }

    override fun onDayEnd(on: LocalDate) {
        daysChecked++
        trialBalance().forEach { (ccy, net) ->
            if (net.signum() != 0) violations += "$on $ccy trial balance off by ${net.toPlainString()}"
        }
    }

    /** Σ debit − Σ credit per currency over every position: zero in a balanced book. Kept as a running total. */
    fun trialBalance(): Map<String, BigDecimal> = TreeMap(net)

    /** The customer's deposit position, credit-positive: what the account holder owns. */
    fun deposit(account: String, currency: String): BigDecimal =
        positions[Position(AccountingClass.CUSTOMER_DEPOSIT, account, currency)]?.negate() ?: BigDecimal.ZERO

    fun violations(): List<String> = violations.toList()

    /** Debit-positive signed amount per position. [dropLineEvery] > 0 loses every n-th line: the harness's own negative case. */
    private fun book(
        cls: AccountingClass,
        account: String,
        currency: String,
        dir: PostingDirection,
        amount: BigDecimal,
    ) {
        lines++
        if (dropLineEvery > 0 && lines % dropLineEvery == 0L) return
        val key = Position(cls, if (cls in PER_CUSTOMER) account else GL, currency)
        val signed = if (dir == PostingDirection.DEBIT) amount else amount.negate()
        positions.merge(key, signed, BigDecimal::add)
        net.merge(currency, signed, BigDecimal::add)
    }

    private companion object {
        const val GL = "GL"
        val PER_CUSTOMER = setOf(AccountingClass.CUSTOMER_DEPOSIT, AccountingClass.ACCRUED_INTEREST_PAYABLE)
    }
}
