// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product

import java.math.BigDecimal

/**
 * Shared, executable form of a product fee-waiver rule (ADR-0138, phase 1b).
 *
 * A product's fee carries `waiveCondition` as free text (in practice, in more than one
 * language — e.g. `"Balance > 50 000 EUR"` and `"Měsíční obrat > 25 000 CZK"`). A
 * condition a human can read but a machine cannot evaluate is, in effect, marketing
 * copy. These types turn that free text into a closed, whitelisted predicate that
 * [WaiverEvaluator] can actually execute. This is deliberately NOT a general expression
 * language: a fixed attribute/operator vocabulary, no scripting, no eval. It lives in
 * `openbank-libs` so the catalog, interest, and eligibility paths share one engine.
 */

/** The closed set of account attributes a waiver rule may test. */
enum class WaiveAttribute(val numeric: Boolean) {
    BALANCE(true),
    MONTHLY_TURNOVER(true),
    AGGREGATE_POCKET_BALANCE(true),
    SEGMENT(false),
    CURRENCY(false),
}

/** The closed set of comparison operators a waiver rule may use. */
enum class WaiveOperator(val symbol: String) {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    EQ("=="),
    NEQ("!="),
}

/** A parsed waiver rule: either an executable comparison or an explicitly un-parseable condition. */
sealed interface WaivePredicate {
    /**
     * An executable comparison. For [WaiveAttribute.numeric] attributes [threshold] (and
     * optionally [currency]) is set and [textValue] is null; for non-numeric attributes
     * [textValue] is set and [threshold]/[currency] are null.
     */
    data class Comparison(
        val attribute: WaiveAttribute,
        val operator: WaiveOperator,
        val threshold: BigDecimal? = null,
        val textValue: String? = null,
        val currency: String? = null,
    ) : WaivePredicate

    /** The condition could not be mapped onto the grammar; it is never coerced into a guessed rule. */
    data class Unparseable(val raw: String, val reason: String) : WaivePredicate
}

/**
 * Best-effort migration parser for the free-text waiver grammar
 * `<attribute-phrase> <operator> <number> [currency]` (numeric attributes) or
 * `<attribute-phrase> (== | !=) <token>` (segment/currency). EN + CS synonyms are
 * recognised; anything outside the grammar returns [WaivePredicate.Unparseable] so the
 * evaluator can fail closed rather than guess.
 */
object WaiveConditionParser {

    private val operatorRegex = Regex(">=|<=|==|!=|>|<|=")

    private val trailingCurrencyRegex = Regex("([A-Za-z]{3})\\s*$")

    private val attributeSynonyms: Map<String, WaiveAttribute> = mapOf(
        "balance" to WaiveAttribute.BALANCE,
        "zůstatek" to WaiveAttribute.BALANCE,
        "zustatek" to WaiveAttribute.BALANCE,
        "monthly turnover" to WaiveAttribute.MONTHLY_TURNOVER,
        "turnover" to WaiveAttribute.MONTHLY_TURNOVER,
        "měsíční obrat" to WaiveAttribute.MONTHLY_TURNOVER,
        "mesicni obrat" to WaiveAttribute.MONTHLY_TURNOVER,
        "obrat" to WaiveAttribute.MONTHLY_TURNOVER,
        "aggregate pocket balance" to WaiveAttribute.AGGREGATE_POCKET_BALANCE,
        "pocket balance" to WaiveAttribute.AGGREGATE_POCKET_BALANCE,
        "souhrnný zůstatek kapes" to WaiveAttribute.AGGREGATE_POCKET_BALANCE,
        "souhrnny zustatek kapes" to WaiveAttribute.AGGREGATE_POCKET_BALANCE,
        "segment" to WaiveAttribute.SEGMENT,
        "currency" to WaiveAttribute.CURRENCY,
        "měna" to WaiveAttribute.CURRENCY,
        "mena" to WaiveAttribute.CURRENCY,
    )

    /** Folds the unicode spaces used as thousands separators (NBSP, narrow NBSP) onto a plain space. */
    private fun normalizeSpaces(s: String): String = s.replace(' ', ' ').replace(' ', ' ')

    fun parse(raw: String?): WaivePredicate {
        if (raw.isNullOrBlank()) return WaivePredicate.Unparseable(raw ?: "", "empty condition")
        val normalized = normalizeSpaces(raw)
        val match = operatorRegex.find(normalized)
            ?: return WaivePredicate.Unparseable(raw, "no comparison operator")

        val left = normalized.substring(0, match.range.first)
        val right = normalized.substring(match.range.last + 1)
        val attribute = matchAttribute(left)
            ?: return WaivePredicate.Unparseable(raw, "unrecognised attribute '${left.trim()}'")
        val operator = operatorFor(match.value)

        return if (attribute.numeric) {
            numericComparison(raw, attribute, operator, right)
        } else {
            textComparison(raw, attribute, operator, right)
        }
    }

    private fun matchAttribute(left: String): WaiveAttribute? {
        val key = left.trim().lowercase().replace(Regex("\\s+"), " ")
        return attributeSynonyms[key]
    }

    private fun operatorFor(symbol: String): WaiveOperator = when (symbol) {
        ">" -> WaiveOperator.GT
        ">=" -> WaiveOperator.GTE
        "<" -> WaiveOperator.LT
        "<=" -> WaiveOperator.LTE
        "!=" -> WaiveOperator.NEQ
        else -> WaiveOperator.EQ // "==" and the lenient single "="
    }

    private fun numericComparison(
        raw: String,
        attribute: WaiveAttribute,
        operator: WaiveOperator,
        right: String,
    ): WaivePredicate {
        var value = right.trim()
        var currency: String? = null
        val ccy = trailingCurrencyRegex.find(value)
        if (ccy != null) {
            currency = ccy.groupValues[1].uppercase()
            value = value.removeRange(ccy.range)
        }
        val digits = value.replace(" ", "").replace(",", "").trim()
        val threshold = digits.toBigDecimalOrNull()
            ?: return WaivePredicate.Unparseable(raw, "unrecognised numeric value '${right.trim()}'")
        return WaivePredicate.Comparison(attribute, operator, threshold = threshold, currency = currency)
    }

    private fun textComparison(
        raw: String,
        attribute: WaiveAttribute,
        operator: WaiveOperator,
        right: String,
    ): WaivePredicate {
        if (operator != WaiveOperator.EQ && operator != WaiveOperator.NEQ) {
            return WaivePredicate.Unparseable(raw, "operator ${operator.symbol} not valid for $attribute")
        }
        val token = right.trim().split(Regex("\\s+")).firstOrNull()?.uppercase()
        if (token.isNullOrBlank()) return WaivePredicate.Unparseable(raw, "missing value for $attribute")
        return WaivePredicate.Comparison(attribute, operator, textValue = token)
    }
}
