// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.ProductState
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One customer movement in a replayed account's event stream: asked of `prePosting`, applied if accepted. */
data class ReplayPosting(val on: LocalDate, val posting: Posting)

/**
 * A synthetic or recorded account (ADR-0352 phase 4): its state when the replay starts and the
 * customer movements it receives, in date order. Several movements on one day are applied in list order.
 */
data class ReplayAccount(val id: String, val initialState: ProductState, val postings: List<ReplayPosting>) {
    init {
        require(id.isNotBlank()) { "account id must not be blank" }
        require(postings.zipWithNext().all { (a, b) -> !b.on.isBefore(a.on) }) {
            "account $id: postings must be in date order"
        }
    }
}

/**
 * The accounts a replay runs. [seed] is the generator seed when the portfolio is synthetic, carried
 * into the diff output so a reported result can be regenerated; null for a recorded history.
 */
data class ReplayPortfolio(val accounts: List<ReplayAccount>, val seed: Long? = null) {
    init {
        require(accounts.map { it.id }.toSet().size == accounts.size) { "account ids must be unique" }
    }
}

/** The simulated calendar: every day in `[from, to]` inclusive, evaluated in [zone]. */
data class ReplayRange(val from: LocalDate, val to: LocalDate, val zone: ZoneId) {
    init {
        require(!to.isBefore(from)) { "range end $to precedes start $from" }
        require(days <= ReplayLimits.MAX_DAYS) { "range of $days days exceeds ${ReplayLimits.MAX_DAYS}" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
}

/**
 * Hard limits of one replay, the run-limit analogue of `IrLimits` (ADR-0352 §2): the calendar is
 * bounded, every evaluation is bounded by the interpreter's own 64-instruction cap, and the number
 * of hooks fired per account-day is bounded by the schedules a revision can declare. A replay
 * therefore terminates by construction; [ReplayResult.evaluations] lets a caller assert it did.
 */
object ReplayLimits {
    /** One hundred years of days; a backtest longer than that is not a backtest. */
    const val MAX_DAYS: Long = 36_525
}

/**
 * Observer of a replay — the shadow-ledger hook. Called in replay order: per day, per account in
 * portfolio order, every instruction list and accepted customer posting; then [onDayEnd] once the
 * whole portfolio has finished that day. Default methods ignore everything.
 */
interface ReplayListener {
    fun onInstructions(accountId: String, on: LocalDate, currency: String, instructions: List<Instruction>) = Unit

    fun onCustomerPosting(accountId: String, on: LocalDate, currency: String, posting: Posting) = Unit

    fun onDayEnd(on: LocalDate) = Unit

    companion object {
        val NONE: ReplayListener = object : ReplayListener {}
    }
}
