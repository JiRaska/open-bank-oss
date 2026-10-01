// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
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

    // ---- Canonical form across 0-, 2- and 3-digit currencies and mixed input scales ----

    private val mixedCurrencyArb = Arb.element("JPY", "EUR", "KWD")
    private val minorUnitsArb = Arb.long(-999_999_999_999L, 999_999_999_999L)

    /** How far the generated input scale is moved off the canonical one, in both directions. */
    private val scaleShiftArb = Arb.int(-6, 6)

    private fun digitsOf(code: String) = CurrencyCode.of(code).defaultFractionDigits

    /**
     * The same VALUE at another scale. Widening appends zeros (always lossless); narrowing strips
     * only zeros that are really there, so the value never changes — only its representation.
     */
    private fun atShiftedScale(canonical: BigDecimal, shift: Int): BigDecimal {
        val target = canonical.scale() + shift
        if (shift >= 0) return canonical.setScale(target)
        val stripped = canonical.stripTrailingZeros()
        return if (stripped.scale() < target) canonical.setScale(target) else stripped
    }

    @Test
    fun `equal values are equal and hash equally whatever scale they arrive in`(): Unit = runBlocking {
        checkAll(mixedCurrencyArb, minorUnitsArb, scaleShiftArb, scaleShiftArb) { code, minor, s1, s2 ->
            val canonical = BigDecimal.valueOf(minor, digitsOf(code))
            val a = Money.of(atShiftedScale(canonical, s1), code)
            val b = Money.of(atShiftedScale(canonical, s2), code)
            assertThat(a).isEqualTo(b)
            assertThat(a.hashCode()).isEqualTo(b.hashCode())
            assertThat(a.amount.scale()).isEqualTo(digitsOf(code))
            assertThat(a.amount).isEqualByComparingTo(canonical)
            assertThat(a.toString()).isEqualTo(b.toString())
        }
    }

    @Test
    fun `compareTo is consistent with equals and with the numeric order`(): Unit = runBlocking {
        checkAll(mixedCurrencyArb, minorUnitsArb, minorUnitsArb, scaleShiftArb) { code, m1, m2, shift ->
            val a = Money.of(atShiftedScale(BigDecimal.valueOf(m1, digitsOf(code)), shift), code)
            val b = Money.of(BigDecimal.valueOf(m2, digitsOf(code)), code)
            assertThat(a.compareTo(b) == 0).isEqualTo(a == b)
            assertThat(Integer.signum(a.compareTo(b))).isEqualTo(m1.compareTo(m2))
            assertThat(Integer.signum(a.compareTo(b))).isEqualTo(-Integer.signum(b.compareTo(a)))
        }
    }

    @Test
    fun `arithmetic results are canonical and equal the directly built value`(): Unit = runBlocking {
        checkAll(mixedCurrencyArb, minorUnitsArb, minorUnitsArb, scaleShiftArb) { code, m1, m2, shift ->
            val digits = digitsOf(code)
            val a = Money.of(atShiftedScale(BigDecimal.valueOf(m1, digits), shift), code)
            val b = Money.of(BigDecimal.valueOf(m2, digits), code)
            assertThat(a + b).isEqualTo(Money.of(BigDecimal.valueOf(m1 + m2, digits), code))
            assertThat(a - b).isEqualTo(Money.of(BigDecimal.valueOf(m1 - m2, digits), code))
            assertThat((a + b) - b).isEqualTo(a)
            assertThat(a + (-a)).isEqualTo(Money.zero(code))
            assertThat(a.abs()).isEqualTo(Money.of(BigDecimal.valueOf(Math.abs(m1), digits), code))
        }
    }

    @Test
    fun `an amount one digit finer than the currency is rejected unless that digit is zero`(): Unit = runBlocking {
        checkAll(mixedCurrencyArb, minorUnitsArb, Arb.int(0, 9)) { code, minor, extraDigit ->
            val digits = digitsOf(code)
            val finer = BigDecimal.valueOf(minor, digits).setScale(digits + 1)
                .add(BigDecimal.valueOf(extraDigit.toLong(), digits + 1))
            if (extraDigit == 0) {
                assertThat(Money.of(finer, code)).isEqualTo(Money.of(BigDecimal.valueOf(minor, digits), code))
            } else {
                assertThatThrownBy { Money.of(finer, code) }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `split and allocate stay exact from any input scale`(): Unit = runBlocking {
        checkAll(mixedCurrencyArb, minorUnitsArb, scaleShiftArb, Arb.int(1, 12)) { code, minor, shift, n ->
            val digits = digitsOf(code)
            val total = Money.of(atShiftedScale(BigDecimal.valueOf(minor, digits), shift), code)
            val parts = total.split(n)
            assertThat(parts).hasSize(n)
            assertThat(parts.fold(Money.zero(code)) { acc, p -> acc + p }).isEqualTo(total)
            assertThat(parts.map { it.amount.scale() }.toSet()).containsExactly(digits)
            val spread = parts.maxOf { it.amount } - parts.minOf { it.amount }
            assertThat(spread).isLessThanOrEqualTo(BigDecimal.ONE.movePointLeft(digits))

            val weighted = total.allocate(1, 2, 3)
            assertThat(weighted.fold(Money.zero(code)) { acc, p -> acc + p }).isEqualTo(total)
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
