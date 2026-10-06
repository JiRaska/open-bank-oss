// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.libs.domain.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {

    @Nested
    inner class Creation {

        @Test
        fun `creates money from BigDecimal and currency`() {
            val money = Money.of(BigDecimal("100.50"), "CZK")
            assertThat(money.amount).isEqualByComparingTo(BigDecimal("100.50"))
            assertThat(money.currency).isEqualTo(CurrencyCode.CZK)
        }

        @Test
        fun `creates money from string amount`() {
            val money = Money.of("250.75", "EUR")
            assertThat(money.amount).isEqualByComparingTo(BigDecimal("250.75"))
            assertThat(money.currency).isEqualTo(CurrencyCode.EUR)
        }

        @Test
        fun `creates zero money`() {
            val zero = Money.zero("USD")
            assertThat(zero.isZero()).isTrue()
            assertThat(zero.currency).isEqualTo(CurrencyCode.USD)
        }

        @Test
        fun `rejects scale exceeding currency fraction digits`() {
            assertThatThrownBy {
                Money(BigDecimal("100.123"), CurrencyCode.CZK) // CZK has 2 fraction digits
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("scale")
        }

        @Test
        fun `accepts zero fraction digits for JPY`() {
            val yen = Money(BigDecimal("1000"), CurrencyCode.of("JPY"))
            assertThat(yen.amount).isEqualByComparingTo(BigDecimal("1000"))
        }
    }

    @Nested
    inner class Arithmetic {

        @Test
        fun `adds two money values`() {
            val a = Money.of("100.00", "CZK")
            val b = Money.of("50.25", "CZK")
            val sum = a + b
            assertThat(sum.amount).isEqualByComparingTo(BigDecimal("150.25"))
        }

        @Test
        fun `subtracts money values`() {
            val a = Money.of("100.00", "EUR")
            val b = Money.of("30.50", "EUR")
            val diff = a - b
            assertThat(diff.amount).isEqualByComparingTo(BigDecimal("69.50"))
        }

        @Test
        fun `negates money`() {
            val money = Money.of("42.00", "CZK")
            val negated = -money
            assertThat(negated.amount).isEqualByComparingTo(BigDecimal("-42.00"))
        }

        @Test
        fun `rejects addition of different currencies`() {
            val czk = Money.of("100.00", "CZK")
            val eur = Money.of("100.00", "EUR")
            assertThatThrownBy { czk + eur }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("currencies")
        }

        @Test
        fun `rejects subtraction of different currencies`() {
            val czk = Money.of("100.00", "CZK")
            val usd = Money.of("50.00", "USD")
            assertThatThrownBy { czk - usd }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `handles large amounts without overflow`() {
            val big = Money.of("99999999999999.99", "CZK")
            val sum = big + Money.of("0.01", "CZK")
            assertThat(sum.amount).isEqualByComparingTo(BigDecimal("100000000000000.00"))
        }
    }

    @Nested
    inner class Predicates {

        @Test
        fun `isPositive for positive amount`() {
            assertThat(Money.of("1.00", "CZK").isPositive()).isTrue()
        }

        @Test
        fun `isPositive false for zero`() {
            assertThat(Money.zero("CZK").isPositive()).isFalse()
        }

        @Test
        fun `isNegative for negative amount`() {
            assertThat(Money.of("-1.00", "CZK").isNegative()).isTrue()
        }

        @Test
        fun `isZero for zero amount`() {
            assertThat(Money.zero("EUR").isZero()).isTrue()
        }

        @Test
        fun `isNonNegative for zero and positive`() {
            assertThat(Money.zero("CZK").isNonNegative()).isTrue()
            assertThat(Money.of("1.00", "CZK").isNonNegative()).isTrue()
            assertThat(Money.of("-1.00", "CZK").isNonNegative()).isFalse()
        }
    }

    @Nested
    inner class Abs {

        @Test
        fun `abs of negative is positive`() {
            val neg = Money.of("-500.00", "CZK")
            assertThat(neg.abs().amount).isEqualByComparingTo(BigDecimal("500.00"))
        }

        @Test
        fun `abs of positive is unchanged`() {
            val pos = Money.of("500.00", "CZK")
            assertThat(pos.abs().amount).isEqualByComparingTo(BigDecimal("500.00"))
        }
    }

    @Nested
    inner class CanonicalScale {

        @Test
        fun `numerically equal amounts are equal and hash equally whatever scale they were built from`() {
            val forms = listOf("1", "1.0", "1.00", "1.000", "1E+0", "0.1E+1", "10E-1").map { Money.of(it, "EUR") }
            forms.forEach {
                assertThat(it).isEqualTo(Money.of("1.00", "EUR"))
                assertThat(it.hashCode()).isEqualTo(Money.of("1.00", "EUR").hashCode())
                assertThat(it.amount.scale()).isEqualTo(2)
                assertThat(it.toString()).isEqualTo("1.00 EUR")
            }
            assertThat(forms.toSet()).hasSize(1)
        }

        @Test
        fun `zero is one value`() {
            assertThat(Money.zero("EUR")).isEqualTo(Money.of("0", "EUR"))
            assertThat(Money.of("0.00", "EUR") - Money.of("0", "EUR")).isEqualTo(Money.zero("EUR"))
            assertThat(-Money.zero("EUR")).isEqualTo(Money.zero("EUR"))
        }

        @Test
        fun `arithmetic results equal the literal`() {
            assertThat(Money.of("0.5", "EUR") + Money.of("0.5", "EUR")).isEqualTo(Money.of("1.00", "EUR"))
            assertThat(Money.of("1", "KWD") - Money.of("0.001", "KWD")).isEqualTo(Money.of("0.999", "KWD"))
        }

        @Test
        fun `exponent notation is held at the currency scale`() {
            val thousand = Money.of("1E+3", "EUR")
            assertThat(thousand.amount.scale()).isEqualTo(2)
            assertThat(thousand.amount.unscaledValue()).isEqualTo(java.math.BigInteger.valueOf(100_000))
            assertThat(Money.of("1E+3", "JPY").amount.scale()).isEqualTo(0)
        }

        @Test
        fun `the scale follows the currency - zero, two and three fraction digits`() {
            assertThat(Money.of("7", "JPY").amount.toPlainString()).isEqualTo("7")
            assertThat(Money.of("7", "EUR").amount.toPlainString()).isEqualTo("7.00")
            assertThat(Money.of("7", "KWD").amount.toPlainString()).isEqualTo("7.000")
        }

        @Test
        fun `an amount that would need rounding is rejected, never rounded`() {
            listOf(
                "1.005" to "EUR",
                "1.5" to "JPY",
                "0.0001" to "KWD",
                "1.0000000000000001" to "EUR",
            ).forEach { (a, c) ->
                assertThatThrownBy { Money.of(a, c) }
                    .describedAs("$a $c")
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("exceeds currency $c fraction digits")
            }
        }

        @Test
        fun `scale is the identity - there is nothing left to scale`() {
            val money = Money.of("10", "CZK")
            assertThat(money.scale()).isSameAs(money)
        }

        @Test
        fun `round to a coarser policy changes the value and keeps the canonical scale`() {
            // TAX_WITHHOLDING is whole units, DOWN: observable, since every HALF_* mode gives 3.
            val rounded = Money.of("2.99", "CZK").round(RoundingPolicy.TAX_WITHHOLDING)
            assertThat(rounded).isEqualTo(Money.of("2.00", "CZK"))
            assertThat(rounded.amount.scale()).isEqualTo(2)
        }

        @Test
        fun `destructures into amount and currency`() {
            val (amount, currency) = Money.of("12.30", "EUR")
            assertThat(amount).isEqualTo(BigDecimal("12.30"))
            assertThat(currency).isEqualTo(CurrencyCode.EUR)
        }
    }

    @Nested
    inner class Magnitude {

        @Test
        fun `the widest supported amount is accepted`() {
            val widest = "9".repeat(Money.MAX_INTEGER_DIGITS) + ".99"
            assertThat(Money.of(widest, "EUR").amount.toPlainString()).isEqualTo(widest)
        }

        @Test
        fun `one integer digit more is rejected`() {
            assertThatThrownBy { Money.of("1" + "0".repeat(Money.MAX_INTEGER_DIGITS), "EUR") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("integer digits")
        }

        @Test
        fun `an exponent far outside the range is rejected from precision and scale alone`() {
            // No time budget: a slow machine would make one flaky and a fast one would hide a
            // regression. What is asserted instead is that these are REJECTED — the check reads two
            // stored fields, and any code path that expanded the digits first could not return.
            listOf("1E+2000000000", "-1E+2000000000", "0E+2000000000", "1E+${Int.MAX_VALUE}").forEach { text ->
                assertThatThrownBy { Money(BigDecimal(text), CurrencyCode.EUR) }
                    .describedAs(text)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("integer digits")
                assertThatThrownBy { Money.of(text, "EUR") }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }

        @Test
        fun `a scale far outside the range is rejected, zero included`() {
            listOf("1E-2000000000", "0E-2000000000", "1E-19").forEach { text ->
                assertThatThrownBy { Money(BigDecimal(text), CurrencyCode.EUR) }
                    .describedAs(text)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("scale")
            }
        }

        @Test
        fun `the widest input scale normalises when the extra digits are zeros`() {
            assertThat(Money(BigDecimal("12.340000000000000000"), CurrencyCode.EUR)).isEqualTo(Money.of("12.34", "EUR"))
        }

        @Test
        fun `amount text longer than the limit is rejected before it is parsed`() {
            assertThatThrownBy { Money.of("1" + "0".repeat(Money.MAX_TEXT_LENGTH), "EUR") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("characters")
        }

        @Test
        fun `text that is not a number is an IllegalArgumentException`() {
            assertThatThrownBy { Money.of("ten", "EUR") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not a decimal number")
        }

        @Test
        fun `arithmetic cannot leave the range`() {
            val widest = Money.of("9".repeat(Money.MAX_INTEGER_DIGITS), "JPY")
            assertThatThrownBy { widest + Money.of("1", "JPY") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("integer digits")
        }
    }

    @Nested
    inner class Ordering {

        @Test
        fun `orders amounts of one currency`() {
            val amounts = listOf("3.10", "-1", "3.1", "0", "12").map { Money.of(it, "EUR") }
            assertThat(amounts.sorted().map { it.amount.toPlainString() })
                .containsExactly("-1.00", "0.00", "3.10", "3.10", "12.00")
            assertThat(amounts.max()).isEqualTo(Money.of("12.00", "EUR"))
            assertThat(Money.of("1", "EUR") < Money.of("1.01", "EUR")).isTrue()
        }

        @Test
        fun `compareTo is zero exactly when equal`() {
            assertThat(Money.of("3.1", "EUR").compareTo(Money.of("3.10", "EUR"))).isZero()
            assertThat(Money.of("3.1", "EUR")).isEqualTo(Money.of("3.10", "EUR"))
        }

        @Test
        fun `comparing different currencies is rejected like any other mixed-currency operation`() {
            assertThatThrownBy { Money.of("1", "EUR").compareTo(Money.of("1", "CZK")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Cannot operate on different currencies: EUR and CZK")
        }

        @Test
        fun `equal amounts in different currencies are not equal`() {
            assertThat(Money.of("1", "EUR")).isNotEqualTo(Money.of("1", "USD"))
        }
    }

    @Nested
    inner class Display {

        @Test
        fun `toString shows amount and currency`() {
            val money = Money.of("1234.56", "EUR")
            assertThat(money.toString()).isEqualTo("1234.56 EUR")
        }
    }
}
