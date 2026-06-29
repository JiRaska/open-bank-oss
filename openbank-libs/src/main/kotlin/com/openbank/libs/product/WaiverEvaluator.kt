// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product

import java.math.BigDecimal

/**
 * The account-side facts a waiver rule is evaluated against. Supplied by the caller;
 * the evaluator persists nothing. Monetary attributes are expressed in [currency] —
 * there is no FX in this phase, so a rule whose threshold names a different currency
 * fails closed (the fee is charged) rather than being silently converted.
 */
data class FeeContext(
    val balance: BigDecimal? = null,
    val monthlyTurnover: BigDecimal? = null,
    val aggregatePocketBalance: BigDecimal? = null,
    val segment: String? = null,
    val currency: String? = null,
)

/** Why a fee ended up waived or charged — machine-readable so ops can see non-evaluable rules. */
enum class WaiveReason {
    NOT_WAIVABLE,
    NO_CONDITION,
    WAIVED_BY_CONDITION,
    CONDITION_NOT_MET,
    CONDITION_NOT_EVALUABLE,
    CONTEXT_UNKNOWN,
    CURRENCY_MISMATCH,
}

/**
 * Pure, stateless evaluation of a fee-waiver condition (ADR-0138). Shared across services
 * via `openbank-libs` so the catalog, interest, and eligibility paths use one engine.
 *
 * Fails closed: if a condition is unparseable, references an attribute the context does
 * not carry, or names a currency the context cannot match, the result is "not waived"
 * with a [WaiveReason] that records why. A fee is never waived on a condition that could
 * not actually be evaluated. [WaiveReason.NOT_WAIVABLE] is reserved for the caller (a fee
 * the product does not mark waivable at all) and is never returned by [evaluate].
 */
object WaiverEvaluator {

    /** Evaluates a free-text [condition] against [context]; returns the condition-derived [WaiveReason]. */
    fun evaluate(condition: String?, context: FeeContext): WaiveReason {
        if (condition.isNullOrBlank()) return WaiveReason.NO_CONDITION
        return when (val predicate = WaiveConditionParser.parse(condition)) {
            is WaivePredicate.Unparseable -> WaiveReason.CONDITION_NOT_EVALUABLE
            is WaivePredicate.Comparison -> evaluate(predicate, context)
        }
    }

    /** True only when [evaluate] returns [WaiveReason.WAIVED_BY_CONDITION]. */
    fun isWaived(condition: String?, context: FeeContext): Boolean =
        evaluate(condition, context) == WaiveReason.WAIVED_BY_CONDITION

    private fun evaluate(c: WaivePredicate.Comparison, context: FeeContext): WaiveReason = when (c.attribute) {
        WaiveAttribute.SEGMENT -> evaluateText(c, context.segment)
        WaiveAttribute.CURRENCY -> evaluateText(c, context.currency)
        WaiveAttribute.BALANCE -> evaluateNumeric(c, context.balance, context.currency)
        WaiveAttribute.MONTHLY_TURNOVER -> evaluateNumeric(c, context.monthlyTurnover, context.currency)
        WaiveAttribute.AGGREGATE_POCKET_BALANCE ->
            evaluateNumeric(c, context.aggregatePocketBalance, context.currency)
    }

    private fun evaluateNumeric(c: WaivePredicate.Comparison, actual: BigDecimal?, ctxCurrency: String?): WaiveReason {
        if (actual == null) return WaiveReason.CONTEXT_UNKNOWN
        if (c.currency != null && !c.currency.equals(ctxCurrency, ignoreCase = true)) {
            return WaiveReason.CURRENCY_MISMATCH
        }
        val threshold = c.threshold ?: return WaiveReason.CONDITION_NOT_EVALUABLE
        val cmp = actual.compareTo(threshold)
        val met = when (c.operator) {
            WaiveOperator.GT -> cmp > 0
            WaiveOperator.GTE -> cmp >= 0
            WaiveOperator.LT -> cmp < 0
            WaiveOperator.LTE -> cmp <= 0
            WaiveOperator.EQ -> cmp == 0
            WaiveOperator.NEQ -> cmp != 0
        }
        return if (met) WaiveReason.WAIVED_BY_CONDITION else WaiveReason.CONDITION_NOT_MET
    }

    private fun evaluateText(c: WaivePredicate.Comparison, actual: String?): WaiveReason {
        if (actual == null) return WaiveReason.CONTEXT_UNKNOWN
        val expected = c.textValue ?: return WaiveReason.CONDITION_NOT_EVALUABLE
        val equal = actual.equals(expected, ignoreCase = true)
        val met = when (c.operator) {
            WaiveOperator.EQ -> equal
            WaiveOperator.NEQ -> !equal
            else -> return WaiveReason.CONDITION_NOT_EVALUABLE
        }
        return if (met) WaiveReason.WAIVED_BY_CONDITION else WaiveReason.CONDITION_NOT_MET
    }
}
