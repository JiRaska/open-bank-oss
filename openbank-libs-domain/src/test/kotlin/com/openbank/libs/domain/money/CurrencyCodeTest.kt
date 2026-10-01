// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.libs.domain.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CurrencyCodeTest {

    @Test
    fun `creates valid currency code`() {
        val czk = CurrencyCode.of("CZK")
        assertThat(czk.code).isEqualTo("CZK")
        assertThat(czk.defaultFractionDigits).isEqualTo(2)
    }

    @Test
    fun `normalizes to uppercase`() {
        val eur = CurrencyCode.of("eur")
        assertThat(eur.code).isEqualTo("EUR")
    }

    @Test
    fun `the constructor form normalises case exactly like of`() {
        assertThat(CurrencyCode("eur")).isEqualTo(CurrencyCode.EUR)
        assertThat(CurrencyCode("eur").code).isEqualTo("EUR")
        assertThat(CurrencyCode("Eur").hashCode()).isEqualTo(CurrencyCode.EUR.hashCode())
    }

    @Test
    fun `an unknown code says it is not ISO 4217`() {
        assertThatThrownBy { CurrencyCode.of("QQQ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown ISO 4217 currency code: QQQ")
        assertThatThrownBy { CurrencyCode("qqq") }.hasMessage("Unknown ISO 4217 currency code: QQQ")
    }

    @Test
    fun `a code that is not three letters says so`() {
        listOf("AB", "EURO", "", "E1R", "€UR", "eu ").forEach { bad ->
            assertThatThrownBy { CurrencyCode(bad) }
                .describedAs("'$bad'")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Currency code must be 3 letters (ISO 4217): '$bad'")
            assertThatThrownBy { CurrencyCode.of(bad) }.hasMessageStartingWith("Currency code must be 3 letters")
        }
    }

    @Test
    fun `an overlong input is not echoed back whole`() {
        assertThatThrownBy { CurrencyCode.of("X".repeat(10_000)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .matches { it.message!!.length < 100 }
    }

    @Test
    fun `a currency without a minor unit is rejected at every entry point`() {
        listOf("XAU", "XAG", "XDR", "XXX").forEach { code ->
            assertThatThrownBy { CurrencyCode.of(code) }
                .describedAs(code)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("has no minor unit")
            assertThatThrownBy { Money.zero(code) }.hasMessageContaining("has no minor unit")
            assertThatThrownBy { Money.of("1", code) }.hasMessageContaining("has no minor unit")
        }
    }

    @Test
    fun `fraction digits are never negative for any accepted code`() {
        java.util.Currency.getAvailableCurrencies().forEach { c ->
            runCatching { CurrencyCode.of(c.currencyCode) }.onSuccess {
                assertThat(it.defaultFractionDigits).describedAs(c.currencyCode).isGreaterThanOrEqualTo(0)
            }
        }
        assertThat(CurrencyCode.of("KWD").defaultFractionDigits).isEqualTo(3)
    }

    @Test
    fun `companion constants are correct`() {
        assertThat(CurrencyCode.CZK.code).isEqualTo("CZK")
        assertThat(CurrencyCode.EUR.code).isEqualTo("EUR")
        assertThat(CurrencyCode.USD.code).isEqualTo("USD")
        assertThat(CurrencyCode.GBP.code).isEqualTo("GBP")
        assertThat(CurrencyCode.CHF.code).isEqualTo("CHF")
    }

    @Test
    fun `fraction digits for JPY is 0`() {
        val jpy = CurrencyCode.of("JPY")
        assertThat(jpy.defaultFractionDigits).isEqualTo(0)
    }

    @Test
    fun `toString returns code`() {
        assertThat(CurrencyCode.CZK.toString()).isEqualTo("CZK")
    }

    @Test
    fun `equality by code`() {
        assertThat(CurrencyCode.of("CZK")).isEqualTo(CurrencyCode.CZK)
    }
}
