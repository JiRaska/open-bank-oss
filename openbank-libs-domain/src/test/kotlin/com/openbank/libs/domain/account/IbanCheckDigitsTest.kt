// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IbanCheckDigitsTest {
    @Test
    fun `computes the published check digits`() {
        // ČNB worked example and the ISO 13616 GB example.
        assertThat(IbanCheckDigits.compute("CZ", "08000000192000145399")).isEqualTo("65")
        assertThat(IbanCheckDigits.compute("GB", "WEST12345698765432")).isEqualTo("82")
    }

    @Test
    fun `single-digit results are zero padded and stay in 02 to 98`() {
        val all = (0 until 2000).map { IbanCheckDigits.compute("CZ", "0800" + it.toString().padStart(16, '0')) }
        assertThat(all).allMatch { it.length == 2 && it.toInt() in 2..98 }
        assertThat(all).anyMatch { it.startsWith("0") }
    }

    @Test
    fun `build yields an IBAN that Iban accepts`() {
        assertThat(IbanCheckDigits.build("CZ", "08000000192000145399").value).isEqualTo("CZ6508000000192000145399")
    }

    @Test
    fun `rejects lower-case or non-alphanumeric input`() {
        assertThatThrownBy { IbanCheckDigits.compute("cz", "0800") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IbanCheckDigits.compute("CZ", "08-00") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IbanCheckDigits.compute("CZ", "") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
