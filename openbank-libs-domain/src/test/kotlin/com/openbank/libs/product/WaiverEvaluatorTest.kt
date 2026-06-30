// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit coverage for the shared fee-waiver engine (ADR-0138 phase 1b): the parser recognises
 * the real, bilingual product conditions, and the evaluator fails closed on anything it
 * cannot actually evaluate.
 */
class WaiverEvaluatorTest {

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
        val r = WaiverEvaluator.evaluate(
            "Balance > 50 000 EUR",
            FeeContext(balance = BigDecimal("60000"), currency = "EUR"),
        )
        assertThat(r).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
        assertThat(
            WaiverEvaluator.isWaived(
                "Balance > 50 000 EUR",
                FeeContext(balance = BigDecimal("60000"), currency = "EUR"),
            ),
        )
            .isTrue()
    }

    @Test
    fun `does not waive when balance is under the threshold`() {
        val r = WaiverEvaluator.evaluate(
            "Balance > 50 000 EUR",
            FeeContext(balance = BigDecimal("40000"), currency = "EUR"),
        )
        assertThat(r).isEqualTo(WaiveReason.CONDITION_NOT_MET)
    }

    @Test
    fun `fails closed on an unparseable condition`() {
        val r = WaiverEvaluator.evaluate(
            "waived for loyal customers",
            FeeContext(balance = BigDecimal("100000"), currency = "EUR"),
        )
        assertThat(r).isEqualTo(WaiveReason.CONDITION_NOT_EVALUABLE)
    }

    @Test
    fun `fails closed when the context attribute is missing`() {
        val r = WaiverEvaluator.evaluate("Balance > 50 000 EUR", FeeContext(currency = "EUR"))
        assertThat(r).isEqualTo(WaiveReason.CONTEXT_UNKNOWN)
    }

    @Test
    fun `fails closed on a currency mismatch`() {
        val r = WaiverEvaluator.evaluate(
            "Balance > 50 000 EUR",
            FeeContext(balance = BigDecimal("60000"), currency = "CZK"),
        )
        assertThat(r).isEqualTo(WaiveReason.CURRENCY_MISMATCH)
    }

    @Test
    fun `a blank condition is NO_CONDITION`() {
        assertThat(WaiverEvaluator.evaluate(null, FeeContext())).isEqualTo(WaiveReason.NO_CONDITION)
        assertThat(WaiverEvaluator.evaluate("  ", FeeContext())).isEqualTo(WaiveReason.NO_CONDITION)
    }

    @Test
    fun `waives by segment equality`() {
        val r = WaiverEvaluator.evaluate("segment == PREMIUM", FeeContext(segment = "premium"))
        assertThat(r).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
    }

    @Test
    fun `evaluates the czech turnover waiver end to end`() {
        val r = WaiverEvaluator.evaluate(
            "Měsíční obrat > 25 000 CZK",
            FeeContext(monthlyTurnover = BigDecimal("30000"), currency = "CZK"),
        )
        assertThat(r).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
    }
}
