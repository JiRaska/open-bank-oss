// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
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
    inner class Scale {

        @Test
        fun `scales to currency fraction digits with HALF_EVEN`() {
            val money = Money(BigDecimal("10"), CurrencyCode.CZK)
            val scaled = money.scale()
            assertThat(scaled.amount.scale()).isEqualTo(2)
            assertThat(scaled.amount).isEqualByComparingTo(BigDecimal("10.00"))
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
