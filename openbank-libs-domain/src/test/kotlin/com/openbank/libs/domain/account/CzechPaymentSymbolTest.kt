// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CzechPaymentSymbolTest {
    @Test
    fun `accepts one to ten digits`() {
        assertThat(CzechPaymentSymbol.isValid("0")).isTrue()
        assertThat(CzechPaymentSymbol.isValid("0308")).isTrue()
        assertThat(CzechPaymentSymbol.isValid("1234567890")).isTrue()
    }

    @Test
    fun `rejects empty, eleven digits, signs, spaces and non-ASCII digits`() {
        assertThat(CzechPaymentSymbol.isValid("")).isFalse()
        assertThat(CzechPaymentSymbol.isValid("12345678901")).isFalse()
        assertThat(CzechPaymentSymbol.isValid("-1")).isFalse()
        assertThat(CzechPaymentSymbol.isValid("12 34")).isFalse()
        assertThat(CzechPaymentSymbol.isValid("١٢٣")).isFalse()
    }

    @Test
    fun `requireValid passes null and names the field`() {
        assertThat(CzechPaymentSymbol.requireValid("ks", null)).isNull()
        assertThatThrownBy { CzechPaymentSymbol.requireValid("ks", "12345678901") }.hasMessageContaining("ks")
    }
}
