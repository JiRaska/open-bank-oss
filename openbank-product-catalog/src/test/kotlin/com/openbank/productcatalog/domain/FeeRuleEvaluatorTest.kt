// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for the configuration-driven fee waiver engine (ADR-0138): the parser
 * recognises the real, bilingual seed conditions, and the evaluator fails closed on
 * anything it cannot actually evaluate.
 */
class FeeRuleEvaluatorTest {

    private fun fee(
        amount: Double = 5.0,
        currency: String = "EUR",
        waivable: Boolean = true,
        condition: String? = null,
    ) = Fee(
        name = "Monthly maintenance",
        type = "MONTHLY",
        amount = amount,
        currency = currency,
        frequency = "MONTHLY",
        waivable = waivable,
        waiveCondition = condition,
    )

    @Test
    fun `parses the personal current balance condition`() {
        val c = WaiveConditionParser.parse("Balance > 50 000 EUR") as WaivePredicate.Comparison
        assertThat(c.attribute).isEqualTo(WaiveAttribute.BALANCE)
        assertThat(c.operator).isEqualTo(WaiveOperator.GT)
        assertThat(c.threshold).isEqualByComparingTo("50000")
        assertThat(c.currency).isEqualTo("EUR")
    }

    @Test
    fun `parses the english turnover condition`() {
        val c = WaiveConditionParser.parse("Monthly turnover > 1 500 EUR") as WaivePredicate.Comparison
        assertThat(c.attribute).isEqualTo(WaiveAttribute.MONTHLY_TURNOVER)
        assertThat(c.threshold).isEqualByComparingTo("1500")
    }

    @Test
    fun `parses the czech turnover condition`() {
        val c = WaiveConditionParser.parse("Měsíční obrat > 25 000 CZK") as WaivePredicate.Comparison
        assertThat(c.attribute).isEqualTo(WaiveAttribute.MONTHLY_TURNOVER)
        assertThat(c.threshold).isEqualByComparingTo("25000")
        assertThat(c.currency).isEqualTo("CZK")
    }

    @Test
    fun `parses the czech aggregate pocket balance condition`() {
        val c = WaiveConditionParser.parse("Souhrnný zůstatek kapes > 20 000 EUR") as WaivePredicate.Comparison
        assertThat(c.attribute).isEqualTo(WaiveAttribute.AGGREGATE_POCKET_BALANCE)
        assertThat(c.threshold).isEqualByComparingTo("20000")
    }

    @Test
    fun `unknown attribute is unparseable`() {
        assertThat(WaiveConditionParser.parse("Karma > 9000"))
            .isInstanceOf(WaivePredicate.Unparseable::class.java)
    }

    @Test
    fun `free text without an operator is unparseable`() {
        assertThat(WaiveConditionParser.parse("waived for loyal customers"))
            .isInstanceOf(WaivePredicate.Unparseable::class.java)
    }

    @Test
    fun `waives when balance is over the threshold`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "Balance > 50 000 EUR"),
            FeeContext(balance = BigDecimal("60000"), currency = "EUR"),
        )
        assertThat(a.waived).isTrue()
        assertThat(a.reason).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
        assertThat(a.effectiveAmount).isEqualByComparingTo("0")
    }

    @Test
    fun `charges when balance is under the threshold`() {
        val a = FeeRuleEvaluator.assess(
            fee(amount = 5.0, condition = "Balance > 50 000 EUR"),
            FeeContext(balance = BigDecimal("40000"), currency = "EUR"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CONDITION_NOT_MET)
        assertThat(a.effectiveAmount).isEqualByComparingTo("5")
    }

    @Test
    fun `fails closed and charges on an unparseable condition`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "waived for loyal customers"),
            FeeContext(balance = BigDecimal("100000"), currency = "EUR"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CONDITION_NOT_EVALUABLE)
    }

    @Test
    fun `fails closed when the context attribute is missing`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "Balance > 50 000 EUR"),
            FeeContext(currency = "EUR"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CONTEXT_UNKNOWN)
    }

    @Test
    fun `fails closed on a currency mismatch`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "Balance > 50 000 EUR"),
            FeeContext(balance = BigDecimal("60000"), currency = "CZK"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CURRENCY_MISMATCH)
    }

    @Test
    fun `a non-waivable fee is always charged`() {
        val a = FeeRuleEvaluator.assess(
            fee(amount = 10.0, waivable = false, condition = "Balance > 1 EUR"),
            FeeContext(balance = BigDecimal("100"), currency = "EUR"),
        )
        assertThat(a.reason).isEqualTo(WaiveReason.NOT_WAIVABLE)
        assertThat(a.effectiveAmount).isEqualByComparingTo("10")
    }

    @Test
    fun `a waivable fee with a blank condition is not waived`() {
        val a = FeeRuleEvaluator.assess(fee(waivable = true, condition = null), FeeContext())
        assertThat(a.reason).isEqualTo(WaiveReason.NO_CONDITION)
        assertThat(a.waived).isFalse()
    }

    @Test
    fun `waives by segment equality`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "segment == PREMIUM"),
            FeeContext(segment = "premium"),
        )
        assertThat(a.waived).isTrue()
        assertThat(a.reason).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
    }

    @Test
    fun `evaluates the czech turnover waiver end to end`() {
        val a = FeeRuleEvaluator.assess(
            fee(currency = "CZK", condition = "Měsíční obrat > 25 000 CZK"),
            FeeContext(monthlyTurnover = BigDecimal("30000"), currency = "CZK"),
        )
        assertThat(a.waived).isTrue()
    }
}
