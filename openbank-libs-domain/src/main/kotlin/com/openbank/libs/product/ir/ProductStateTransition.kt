// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Instruction kind, then line, then accounting class: the depth is the shape of the data.
@file:Suppress("NestedBlockDepth")

package com.openbank.libs.product.ir

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The reference effect of a [Instruction] list on a [ProductState]: what the golden runner, the
 * simulation and a shadow comparator apply between two evaluations. Pure. A production consumer
 * posts to the ledger instead; this is the model of what that posting does to the two balances
 * the interpreter reads (`balance`, `accruedInterest`) and to the parameter overrides.
 */
object ProductStateTransition {

    fun apply(state: ProductState, instructions: List<Instruction>, on: LocalDate): ProductState {
        var balance = state.balance
        var accrued = state.accruedInterest
        val params = LinkedHashMap(state.parameters)
        val events = ArrayList(state.events)
        for (i in instructions) {
            when (i) {
                is Instruction.Post -> for (l in i.lines) {
                    val signed = if (l.direction == PostingDirection.CREDIT) l.amount else l.amount.negate()
                    when (l.accountingClass) {
                        AccountingClass.CUSTOMER_DEPOSIT -> {
                            balance += signed
                            events += AccountEvent(eventKindOf(i), on, l.amount)
                        }
                        AccountingClass.ACCRUED_INTEREST_PAYABLE -> accrued += signed
                        else -> Unit
                    }
                }
                is Instruction.Reprice -> params[i.parameter] = i.value
                is Instruction.Reject, is Instruction.Schedule, is Instruction.Notify -> Unit
            }
        }
        return state.copy(
            balance = balance,
            accruedInterest = accrued,
            parameters = params,
            events = events,
            balanceSamples = state.balanceSamples + BalanceSample(on, balance),
        )
    }

    /**
     * The account event a `Post` touching the customer deposit represents, read from its COUNTER
     * line's accounting class — never from the direction alone, which would record a maturity
     * pay-out or a penalty as a fee and make `countEvents(FEE, …)` miscount.
     */
    fun eventKindOf(post: Instruction.Post): EventKind {
        val counter = post.lines.firstOrNull { it.accountingClass != AccountingClass.CUSTOMER_DEPOSIT }?.accountingClass
        return when (counter) {
            AccountingClass.ACCRUED_INTEREST_PAYABLE, AccountingClass.INTEREST_EXPENSE -> EventKind.INTEREST
            AccountingClass.FEE_INCOME -> EventKind.FEE
            AccountingClass.PENALTY_INCOME -> EventKind.PENALTY
            AccountingClass.SETTLEMENT -> EventKind.TRANSFER_OUT
            AccountingClass.CUSTOMER_DEPOSIT, null -> EventKind.TRANSFER_IN
        }
    }

    /** Applies a customer posting (the thing a `prePosting` hook was asked about) once it is accepted. */
    fun applyPosting(state: ProductState, posting: Posting, on: LocalDate): ProductState {
        val signed = if (posting.direction == PostingDirection.CREDIT) posting.amount else posting.amount.negate()
        val balance: BigDecimal = state.balance + signed
        return state.copy(
            balance = balance,
            events = state.events + AccountEvent(posting.kind, on, posting.amount),
            balanceSamples = state.balanceSamples + BalanceSample(on, balance),
        )
    }
}
