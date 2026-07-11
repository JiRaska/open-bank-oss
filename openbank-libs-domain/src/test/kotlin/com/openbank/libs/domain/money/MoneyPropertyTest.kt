// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Property-based invariants for [Money] arithmetic (ADR-0011 L1, issue #469). [MoneyTest] pins
 * specific hand-picked cases; this suite asserts the invariants hold across hundreds of randomly
 * generated amounts and currency pairs — the "for all" guarantee example tests can't give.
 *
 * All generated currencies here have 2 fraction digits, so amounts are generated in cents
 * (a scaled [Long]) to always land on a valid [Money] scale — mirrors the amountArb pattern in
 * openbank-ledger-service's JournalEntryPropertyTest.
 */
class MoneyPropertyTest {

    private val currencyArb = Arb.element("CZK", "EUR", "USD", "GBP", "CHF")
    private val centsArb = Arb.long(-999_999_999L, 999_999_999L)
    private val amountArb = centsArb.map { BigDecimal(it).movePointLeft(2) }

    @Test
    fun `addition is commutative within a currency`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb, amountArb) { currency, x, y ->
            val a = Money.of(x, currency)
            val b = Money.of(y, currency)
            assertThat((a + b).amount).isEqualByComparingTo((b + a).amount)
        }
    }

    @Test
    fun `addition is associative within a currency`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb, amountArb, amountArb) { currency, x, y, z ->
            val a = Money.of(x, currency)
            val b = Money.of(y, currency)
            val c = Money.of(z, currency)
            assertThat(((a + b) + c).amount).isEqualByComparingTo((a + (b + c)).amount)
        }
    }

    @Test
    fun `subtraction is the inverse of addition`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb, amountArb) { currency, x, y ->
            val a = Money.of(x, currency)
            val b = Money.of(y, currency)
            assertThat(((a + b) - b).amount).isEqualByComparingTo(a.amount)
        }
    }

    @Test
    fun `a value plus its negation is always zero`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb) { currency, x ->
            val a = Money.of(x, currency)
            assertThat((a + (-a)).amount).isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Test
    fun `double negation round-trips to the original value`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb) { currency, x ->
            val a = Money.of(x, currency)
            assertThat((-(-a)).amount).isEqualByComparingTo(a.amount)
        }
    }

    @Test
    fun `round-tripping through its own plain-string representation loses no precision`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb) { currency, x ->
            val a = Money.of(x, currency)
            val roundTripped = Money.of(a.amount.toPlainString(), currency)
            assertThat(roundTripped.amount).isEqualByComparingTo(a.amount)
            assertThat(roundTripped).isEqualTo(a)
        }
    }

    @Test
    fun `addition across different currencies is always rejected`(): Unit = runBlocking {
        checkAll(currencyArb, currencyArb, amountArb, amountArb) { c1, c2, x, y ->
            if (c1 != c2) {
                val a = Money.of(x, c1)
                val b = Money.of(y, c2)
                assertThatThrownBy { a + b }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `subtraction across different currencies is always rejected`(): Unit = runBlocking {
        checkAll(currencyArb, currencyArb, amountArb, amountArb) { c1, c2, x, y ->
            if (c1 != c2) {
                val a = Money.of(x, c1)
                val b = Money.of(y, c2)
                assertThatThrownBy { a - b }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `scale is always idempotent and never exceeds the currency fraction digits`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb) { currency, x ->
            val a = Money.of(x, currency)
            val scaledOnce = a.scale()
            val scaledTwice = scaledOnce.scale()
            assertThat(scaledOnce.amount.scale()).isLessThanOrEqualTo(scaledOnce.currency.defaultFractionDigits)
            assertThat(scaledTwice.amount).isEqualByComparingTo(scaledOnce.amount)
        }
    }

    @Test
    fun `abs is idempotent and always non-negative`(): Unit = runBlocking {
        checkAll(currencyArb, amountArb) { currency, x ->
            val a = Money.of(x, currency)
            assertThat(a.abs().isNonNegative()).isTrue()
            assertThat(a.abs().abs().amount).isEqualByComparingTo(a.abs().amount)
        }
    }
}
