// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CzechPaymentSymbolTest {
    @Test
    fun `variable and specific symbols take one to ten digits`() {
        listOf(CzechPaymentSymbol.VARIABLE, CzechPaymentSymbol.SPECIFIC).forEach { kind ->
            assertThat(kind.isValid("0")).describedAs(kind.name).isTrue()
            assertThat(kind.isValid("1234567890")).describedAs(kind.name).isTrue()
            assertThat(kind.isValid("12345678901")).describedAs(kind.name).isFalse()
        }
    }

    @Test
    fun `the constant symbol stops at four digits, as the domestic-payment column does`() {
        assertThat(CzechPaymentSymbol.CONSTANT.isValid("0308")).isTrue()
        assertThat(CzechPaymentSymbol.CONSTANT.isValid("9999")).isTrue()
        assertThat(CzechPaymentSymbol.CONSTANT.isValid("12345")).isFalse()
        assertThat(CzechPaymentSymbol.CONSTANT.isValid("1234567890")).isFalse()
    }

    @Test
    fun `rejects empty, signs, spaces and non-ASCII digits`() {
        CzechPaymentSymbol.entries.forEach { kind ->
            listOf("", "-1", "12 3", "١٢٣").forEach {
                assertThat(kind.isValid(it)).describedAs("$kind '$it'").isFalse()
            }
        }
    }

    @Test
    fun `normalise treats null and blank as absent and names the field on a bad value`() {
        assertThat(CzechPaymentSymbol.CONSTANT.normalise(null)).isNull()
        assertThat(CzechPaymentSymbol.CONSTANT.normalise("  ")).isNull()
        assertThat(CzechPaymentSymbol.CONSTANT.normalise(" 0308 ")).isEqualTo("0308")
        assertThatThrownBy { CzechPaymentSymbol.CONSTANT.normalise("12345") }
            .hasMessageContaining("constantSymbol").hasMessageContaining("1-4")
    }
}
