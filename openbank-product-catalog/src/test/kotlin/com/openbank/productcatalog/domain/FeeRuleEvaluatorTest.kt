// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Coverage for the [Fee]-typed adapter (ADR-0138). The shared parsing/evaluation engine is
 * tested in openbank-libs (WaiverEvaluatorTest); here we only assert the Fee mapping:
 * waivable handling, effective amount, currency, and the surfaced [WaiveReason].
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
    fun `a non-waivable fee is always charged`() {
        val a = FeeRuleEvaluator.assess(
            fee(amount = 10.0, waivable = false, condition = "Balance > 1 EUR"),
            FeeContext(balance = BigDecimal("100"), currency = "EUR"),
        )
        assertThat(a.reason).isEqualTo(WaiveReason.NOT_WAIVABLE)
        assertThat(a.waived).isFalse()
        assertThat(a.effectiveAmount).isEqualByComparingTo("10")
    }

    @Test
    fun `a satisfied condition waives the fee to zero`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "Balance > 50 000 EUR"),
            FeeContext(balance = BigDecimal("60000"), currency = "EUR"),
        )
        assertThat(a.waived).isTrue()
        assertThat(a.reason).isEqualTo(WaiveReason.WAIVED_BY_CONDITION)
        assertThat(a.effectiveAmount).isEqualByComparingTo("0")
    }

    @Test
    fun `an unmet condition charges the full fee`() {
        val a = FeeRuleEvaluator.assess(
            fee(amount = 5.0, condition = "Balance > 50 000 EUR"),
            FeeContext(balance = BigDecimal("40000"), currency = "EUR"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CONDITION_NOT_MET)
        assertThat(a.effectiveAmount).isEqualByComparingTo("5")
    }

    @Test
    fun `a non-evaluable condition fails closed and charges`() {
        val a = FeeRuleEvaluator.assess(
            fee(condition = "waived for loyal customers"),
            FeeContext(balance = BigDecimal("100000"), currency = "EUR"),
        )
        assertThat(a.waived).isFalse()
        assertThat(a.reason).isEqualTo(WaiveReason.CONDITION_NOT_EVALUABLE)
    }

    @Test
    fun `a waivable fee with a blank condition is not waived`() {
        val a = FeeRuleEvaluator.assess(fee(waivable = true, condition = null), FeeContext())
        assertThat(a.reason).isEqualTo(WaiveReason.NO_CONDITION)
        assertThat(a.waived).isFalse()
    }
}
