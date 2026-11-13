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

/**
 * Follows each account's accrued-interest-payable position through a replay and records what every
 * capitalisation leaves behind. Under IR v1 a capitalisation posts the accrued amount rounded
 * HALF_UP to the cent, so the residue it leaves is the rounding difference: at most half a cent
 * either way, and negative whenever the cent was rounded up. That is a named IR v1 limitation, not
 * a pass; the residue sweep and an explicit capitalisation rounding mode are IR v2 work.
 *
 * Forwards every callback to [delegate] (the shadow book), so one replay feeds both.
 */
class CapitalisationResidueTracker(private val delegate: ReplayListener) : ReplayListener {

    /** Per-account closure figures: everything that moved the accrued-interest-payable position. */
    data class Closure(
        val opening: BigDecimal,
        val accrued: BigDecimal,
        val capitalised: BigDecimal,
        val otherDebits: BigDecimal,
        val residues: List<BigDecimal>,
    ) {
        /** The position the movements imply: opening + accrued - capitalised - other debits. */
        val implied: BigDecimal get() = opening + accrued - capitalised - otherDebits
    }

    private class Running(val opening: BigDecimal) {
        var position: BigDecimal = opening
        var accrued: BigDecimal = BigDecimal.ZERO
        var capitalised: BigDecimal = BigDecimal.ZERO
        var otherDebits: BigDecimal = BigDecimal.ZERO
        val residues = ArrayList<BigDecimal>()
    }

    private val accounts = LinkedHashMap<String, Running>()

    fun open(portfolio: ReplayPortfolio) {
        for (a in portfolio.accounts) accounts[a.id] = Running(a.initialState.accruedInterest)
    }

    override fun onInstructions(accountId: String, on: LocalDate, currency: String, instructions: List<Instruction>) {
        delegate.onInstructions(accountId, on, currency, instructions)
        val r = accounts.getValue(accountId)
        var capitalisedHere = false
        for (post in instructions.filterIsInstance<Instruction.Post>()) {
            capitalisedHere = track(r, post) || capitalisedHere
        }
        if (capitalisedHere) r.residues += r.position
    }

    /** Applies [post]'s accrued-interest-payable lines to [r]; true when it was a capitalisation. */
    private fun track(r: Running, post: Instruction.Post): Boolean {
        val capitalisation = post.lines.any {
            it.accountingClass == AccountingClass.CUSTOMER_DEPOSIT && it.direction == PostingDirection.CREDIT
        }
        var debited = false
        for (l in post.lines.filter { it.accountingClass == AccountingClass.ACCRUED_INTEREST_PAYABLE }) {
            if (l.direction == PostingDirection.CREDIT) {
                r.accrued += l.amount
                r.position += l.amount
                continue
            }
            if (capitalisation) r.capitalised += l.amount else r.otherDebits += l.amount
            r.position -= l.amount
            debited = true
        }
        return capitalisation && debited
    }

    override fun onCustomerPosting(accountId: String, on: LocalDate, currency: String, posting: Posting) =
        delegate.onCustomerPosting(accountId, on, currency, posting)

    override fun onDayEnd(on: LocalDate) = delegate.onDayEnd(on)

    fun closure(accountId: String): Closure = accounts.getValue(accountId).let {
        Closure(it.opening, it.accrued, it.capitalised, it.otherDebits, it.residues.toList())
    }

    /** The account's accrued-interest-payable position as tracked, for reconciling against the book. */
    fun position(accountId: String): BigDecimal = accounts.getValue(accountId).position

    val accountIds: Set<String> get() = accounts.keys

    companion object {
        /** The most one IR v1 HALF_UP capitalisation can leave behind, either way. */
        val V1_RESIDUE_BOUND: BigDecimal = BigDecimal("0.005")
    }
}
