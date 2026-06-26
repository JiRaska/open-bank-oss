// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
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
    fun `rejects invalid currency code`() {
        assertThatThrownBy { CurrencyCode.of("QQQ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects wrong length`() {
        assertThatThrownBy { CurrencyCode("AB") }
            .isInstanceOf(IllegalArgumentException::class.java)
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
