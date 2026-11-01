// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator

/**
 * Static monotonicity check (ADR-0352 §9 phase 6): does a revision's accrual ever FALL as the
 * balance rises?
 *
 * IR v1 does not guarantee monotone accrual — a v1 language limitation, kept because v1 semantics
 * are frozen. A bonus or an accrue rule may be gated by a predicate that reads the balance, so
 * `BALANCE LT x` is a legal "bonus below x" that accrues less above x, and a tier may pay less than
 * the tier below it. This check flags every such construct. It does NOT reject the revision: it is
 * advice for authors and reviewers, and the condition under which
 * `ProductFormalPropertiesTest` holds the monotone-accrual property.
 *
 * Conservative by construction: a balance-reading predicate whose truth can switch from true to
 * false as the balance rises is flagged wherever it sits — under `not` its direction flips, and
 * under `or` it may still decide the outcome. `balanceMin` reads the balance history, which rises
 * with the balance too, so it is treated the same way.
 *
 * TODO(#14205): call [findings] from `obp lint` and the MCP lint tool once the shared
 * `ProductToolkit` lands, so that all three surfaces report the same findings.
 */
object ProductMonotonicity {
    /** A construct that lets accrual fall as the balance rises. [ruleId] names where it was found. */
    data class Finding(val ruleId: String, val message: String)

    fun findings(rev: ProductRevisionIr): List<Finding> = buildList {
        val terms = rev.interest ?: return@buildList
        if (terms.rateType == RateType.TIERED) {
            terms.tiers.zipWithNext().forEachIndexed { i, (lo, hi) ->
                if (hi.rate < lo.rate) {
                    add(Finding(terms.ruleId, "tier ${i + 1} rate ${hi.rate} is lower than tier $i rate ${lo.rate}"))
                }
            }
        }
        terms.bonus?.let { b -> falling(b.predicate).forEach { add(Finding(b.ruleId, "bonus is gated by $it")) } }
        // Every hook, not only `scheduled`: an accrue rule is legal wherever the schema allows one.
        rev.hooks.values.flatten().filter { it.action == Action.AccrueInterest }.forEach { rule ->
            rule.whenPredicate?.let { p -> falling(p).forEach { add(Finding(rule.ruleId, "accrual is gated by $it")) } }
        }
    }

    fun isMonotone(rev: ProductRevisionIr): Boolean = findings(rev).isEmpty()

    /** Balance-reading leaves of [p] that may turn false as the balance rises, given the polarity they sit at. */
    private fun falling(p: Predicate, positive: Boolean = true): List<String> = when (p) {
        is Predicate.Comparison ->
            if (p.attribute in BALANCE_ATTRIBUTES && !risesWith(p.operator, positive)) {
                listOf(describe(p.attribute.name, p.operator, positive, p.threshold))
            } else {
                emptyList()
            }
        is Predicate.BalanceMin ->
            if (!risesWith(p.operator, positive)) {
                listOf(describe("balanceMin(${p.window.days}d)", p.operator, positive, p.amount))
            } else {
                emptyList()
            }
        is Predicate.And -> p.operands.flatMap { falling(it, positive) }
        is Predicate.Or -> p.operands.flatMap { falling(it, positive) }
        is Predicate.Not -> falling(p.operand, !positive)
        // The projected balance is the balance after the posting, so it rises with the balance; the
        // posting amount does not depend on the balance at all.
        is Predicate.PostingComparison ->
            if (p.attribute == PostingAttribute.PROJECTED_BALANCE && !risesWith(p.operator, positive)) {
                listOf(describe(p.attribute.name, p.operator, positive, p.threshold))
            } else {
                emptyList()
            }
        is Predicate.NoEvents, is Predicate.CountEvents, is Predicate.SumEvents -> emptyList()
    }

    /** True when `x <op> t` (or its negation, when not [positive]) can only go false -> true as x rises. */
    private fun risesWith(op: WaiveOperator, positive: Boolean): Boolean = when (op) {
        WaiveOperator.GT, WaiveOperator.GTE -> positive
        WaiveOperator.LT, WaiveOperator.LTE -> !positive
        WaiveOperator.EQ, WaiveOperator.NEQ -> false
    }

    private fun describe(subject: String, op: WaiveOperator, positive: Boolean, threshold: Any?) =
        (if (positive) "" else "not ") + "$subject ${op.symbol} $threshold"

    private val BALANCE_ATTRIBUTES = setOf(WaiveAttribute.BALANCE, WaiveAttribute.AGGREGATE_POCKET_BALANCE)
}
