// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.account.application.usecase.IbanGenerator
import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CzechIbanValidityTest {

    @Test
    fun `every generated number is nationally valid`() {
        val generator = IbanGenerator("0000")
        repeat(200) {
            assertThat(CzechIbanValidity.isNationallyValid(generator.generate(CurrencyCode.of("CZK")).value)).isTrue()
        }
    }

    @Test
    fun `a base whose weighted sum is not divisible by 11 is invalid`() {
        // base 0000000002: weight 1 * 2 = 2 -> 2 mod 11 != 0
        assertThat(CzechIbanValidity.isNationallyValid("CZ0000000000000000000002")).isFalse()
    }

    @Test
    fun `a prefix failing mod-11 is invalid even when the base is valid`() {
        // prefix 000001 (weight 1) -> 1 mod 11 != 0; base 0000000011 (1*10... checked below) is valid
        val base = com.openbank.libs.domain.account.CzechAccountNumber.generateBase()
        assertThat(CzechIbanValidity.isNationallyValid("CZ00" + "0000" + "000001" + base)).isFalse()
    }

    @Test
    fun `a malformed or short CZ value is invalid and a non-CZ value is out of scope`() {
        assertThat(CzechIbanValidity.isNationallyValid("CZ65080000001920001453")).isFalse()
        assertThat(CzechIbanValidity.isNationallyValid("DE89370400440532013000")).isTrue()
    }
}
