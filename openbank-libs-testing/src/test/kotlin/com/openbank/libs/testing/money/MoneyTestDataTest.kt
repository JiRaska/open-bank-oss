// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.money

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTestDataTest {

    @Test
    fun `zero is zero`() {
        assertThat(MoneyTestData.zero("CZK").isZero()).isTrue()
    }

    @Test
    fun `minPositive and minNegative are exact negations`() {
        val pos = MoneyTestData.minPositive("EUR")
        val neg = MoneyTestData.minNegative("EUR")
        assertThat((pos + neg).amount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `largePositive and largeNegative are exact negations`() {
        val pos = MoneyTestData.largePositive("USD")
        val neg = MoneyTestData.largeNegative("USD")
        assertThat((pos + neg).amount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `boundaryAmounts covers zero, both min and both max`() {
        val amounts = MoneyTestData.boundaryAmounts("GBP")
        assertThat(amounts).hasSize(5)
        assertThat(amounts.count { it.isZero() }).isEqualTo(1)
        assertThat(amounts.count { it.isPositive() }).isEqualTo(2)
        assertThat(amounts.count { it.isNegative() }).isEqualTo(2)
    }

    @Test
    fun `balancedPair sums to zero for any generated amount`(): Unit = runBlocking {
        checkAll(MoneyArb.currency(), MoneyArb.money("CZK")) { currency, generated ->
            val (a, b) = MoneyTestData.balancedPair(generated.amount.abs().toPlainString(), currency)
            assertThat((a + b).amount).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(a.currency.code).isEqualTo(currency)
            assertThat(b.currency.code).isEqualTo(currency)
        }
    }

    @Test
    fun `MoneyArb money always lands on a valid scale for every common currency`(): Unit = runBlocking {
        MoneyTestData.COMMON_CURRENCIES.forEach { currency ->
            checkAll(MoneyArb.money(currency)) { generated ->
                assertThat(generated.amount.scale()).isLessThanOrEqualTo(generated.currency.defaultFractionDigits)
                assertThat(generated.currency.code).isEqualTo(currency)
            }
        }
    }
}
