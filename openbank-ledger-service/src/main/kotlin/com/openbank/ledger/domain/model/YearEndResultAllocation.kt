// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.math.BigDecimal
import java.security.MessageDigest

/** One closing leg of the year-end result allocation: the account, the side and the amount. */
data class ResultAllocationLeg(val glAccountCode: String, val side: JournalSide, val amount: BigDecimal)

/**
 * The year-end result allocation (ADR-0096 D3): the closing journal that zeroes every income and
 * expense account for the fiscal year and carries the net result to retained earnings.
 *
 * Carry-relative, the same property `FxRevaluationPosting` relies on: the plan is computed from the
 * fiscal year's trial balance, and the closing journal is dated INSIDE that year, so once it posts
 * the next computation reads its own predecessor and finds nothing left. A posting that lands in
 * the year after the allocation leaves a residual, and allocating again posts only that residual —
 * never the whole result a second time.
 *
 * [netResult] follows the trial-balance sign (debit minus credit): negative is a profit (credits
 * on income exceed debits on expense), positive a loss.
 */
data class YearEndResultAllocationPlan(
    val fiscalYear: Int,
    val legs: List<ResultAllocationLeg>,
    val netResult: BigDecimal,
) {
    val isEmpty: Boolean get() = legs.isEmpty()

    /**
     * Identity of exactly what this plan closes — sorted `code=net` pairs. Part of the posting's
     * idempotency key, so a re-run over an unchanged ledger is a no-op while a residual (a
     * different plan) is a different key and can post.
     */
    fun digest(): String {
        val canonical = legs.sortedBy { it.glAccountCode }
            .joinToString(";") { "${it.glAccountCode}=${it.side}:${canonicalNumber(it.amount)}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(DIGEST_HEX_CHARS)
    }

    companion object {
        const val RESULT_CURRENCY = "CZK"
        private const val DIGEST_HEX_CHARS = 16

        /**
         * Builds the plan from a fiscal year's trial-balance lines.
         *
         * Fails closed on a non-CZK income or expense balance. Retained earnings is a CZK equity
         * account (it is what own funds reads), and a per-currency ledger cannot move a EUR result
         * into it without a translation at a year-end rate. Inventing that translation here would
         * post a plausible number nobody decided on; the caller must revalue or otherwise clear the
         * foreign-currency P&L first, and the refusal names exactly which balances block.
         */
        fun plan(
            fiscalYear: Int,
            lines: List<TrialBalanceLine>,
            retainedEarningsCode: String,
        ): YearEndResultAllocationPlan {
            val pnl = lines.filter { it.type == GlAccountType.INCOME || it.type == GlAccountType.EXPENSE }
                .filter { it.net.signum() != 0 }
            val foreign = pnl.filter { it.currency != RESULT_CURRENCY }
            checkConflict(foreign.isEmpty()) {
                "Fiscal year $fiscalYear has non-$RESULT_CURRENCY income/expense balances " +
                    foreign.sortedWith(compareBy({ it.code }, { it.currency }))
                        .joinToString { "${it.code} ${it.currency} ${it.net.toPlainString()}" } +
                    " — revalue them before allocating the result to retained earnings"
            }
            // Several lines per code are possible (one per currency); only CZK survives the check,
            // but aggregate per code anyway so the plan has at most one leg per account.
            val netByCode = pnl.groupBy { it.code }
                .mapValues { (_, ls) -> ls.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) } }
                .filterValues { it.signum() != 0 }
                .toSortedMap()
            val closing = netByCode.map { (code, net) ->
                // Reverse the balance: a debit-heavy account is credited, a credit-heavy one debited.
                ResultAllocationLeg(code, if (net.signum() > 0) JournalSide.CREDIT else JournalSide.DEBIT, net.abs())
            }
            val netResult = netByCode.values.fold(BigDecimal.ZERO) { acc, n -> acc.add(n) }
            val retained = when (netResult.signum()) {
                0 -> emptyList()
                // A loss (debit result) is carried as a debit to retained earnings; a profit as a credit.
                1 -> listOf(ResultAllocationLeg(retainedEarningsCode, JournalSide.DEBIT, netResult))
                else -> listOf(ResultAllocationLeg(retainedEarningsCode, JournalSide.CREDIT, netResult.abs()))
            }
            return YearEndResultAllocationPlan(fiscalYear, closing + retained, netResult)
        }
    }
}
