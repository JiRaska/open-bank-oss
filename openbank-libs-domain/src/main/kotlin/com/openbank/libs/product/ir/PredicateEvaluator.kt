// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One branch per predicate of the closed language (ADR-0352 §1).
@file:Suppress("CyclomaticComplexMethod")

package com.openbank.libs.product.ir

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Evaluates the closed predicate language over a [ProductState] snapshot. Pure. Fails closed the
 * way [com.openbank.libs.product.WaiverEvaluator] does: an attribute the state does not carry, a
 * currency the state cannot match, or a window with no balance sample evaluates to `false`.
 */
object PredicateEvaluator {

    fun test(p: Predicate, state: ProductState, posting: Posting?, today: LocalDate): Boolean = when (p) {
        is Predicate.And -> p.operands.all { test(it, state, posting, today) }
        is Predicate.Or -> p.operands.any { test(it, state, posting, today) }
        is Predicate.Not -> !test(p.operand, state, posting, today)
        is Predicate.Comparison -> comparison(p, state)
        is Predicate.PostingComparison -> postingComparison(p, state, posting)
        is Predicate.NoEvents -> state.events.none { it.kind == p.kind && inWindow(it.at, p.window, today) }
        is Predicate.CountEvents -> compare(
            BigDecimal(state.events.count { it.kind == p.kind && inWindow(it.at, p.window, today) }),
            p.operator,
            BigDecimal(p.count),
        )
        is Predicate.SumEvents -> compare(
            state.events.filter { it.kind == p.kind && inWindow(it.at, p.window, today) }
                .fold(BigDecimal.ZERO) { a, e -> a + e.amount },
            p.operator,
            p.amount,
        )
        is Predicate.BalanceMin -> state.balanceSamples.filter { inWindow(it.on, p.window, today) }
            .minOfOrNull { it.balance }
            ?.let { compare(it, p.operator, p.amount) } ?: false
    }

    /** `[today - (days - 1), today]`, inclusive of today. */
    private fun inWindow(on: LocalDate, w: Window, today: LocalDate): Boolean =
        !on.isAfter(today) && !on.isBefore(today.minusDays((w.days - 1).toLong()))

    private fun comparison(c: Predicate.Comparison, s: ProductState): Boolean = when (c.attribute) {
        WaiveAttribute.SEGMENT -> text(c, s.segment)
        WaiveAttribute.CURRENCY -> text(c, s.currency)
        WaiveAttribute.BALANCE -> numeric(c, s.balance, s.currency)
        WaiveAttribute.MONTHLY_TURNOVER -> numeric(c, s.monthlyTurnover, s.currency)
        WaiveAttribute.AGGREGATE_POCKET_BALANCE -> numeric(c, s.aggregatePocketBalance, s.currency)
    }

    private fun numeric(c: Predicate.Comparison, actual: BigDecimal?, ctxCurrency: String): Boolean {
        if (actual == null) return false
        if (c.currency != null && !c.currency.equals(ctxCurrency, ignoreCase = true)) return false
        val threshold = c.threshold ?: return false
        return compare(actual, c.operator, threshold)
    }

    private fun text(c: Predicate.Comparison, actual: String?): Boolean {
        if (actual == null) return false
        val expected = c.textValue ?: return false
        val equal = actual.equals(expected, ignoreCase = true)
        return when (c.operator) {
            WaiveOperator.EQ -> equal
            WaiveOperator.NEQ -> !equal
            else -> false
        }
    }

    private fun postingComparison(c: Predicate.PostingComparison, s: ProductState, posting: Posting?): Boolean {
        posting ?: return false
        val signed = if (posting.direction == PostingDirection.CREDIT) posting.amount else posting.amount.negate()
        val actual = when (c.attribute) {
            PostingAttribute.POSTING_AMOUNT -> posting.amount
            PostingAttribute.PROJECTED_BALANCE -> s.balance + signed
        }
        return compare(actual, c.operator, c.threshold)
    }

    fun compare(actual: BigDecimal, op: WaiveOperator, threshold: BigDecimal): Boolean {
        val cmp = actual.compareTo(threshold)
        return when (op) {
            WaiveOperator.GT -> cmp > 0
            WaiveOperator.GTE -> cmp >= 0
            WaiveOperator.LT -> cmp < 0
            WaiveOperator.LTE -> cmp <= 0
            WaiveOperator.EQ -> cmp == 0
            WaiveOperator.NEQ -> cmp != 0
        }
    }
}
