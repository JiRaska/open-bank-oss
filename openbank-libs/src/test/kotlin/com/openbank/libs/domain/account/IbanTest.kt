// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IbanTest {

    @Nested
    inner class Validation {

        @Test
        fun `validates correct Czech IBAN`() {
            assertThat(Iban.isValid("CZ65 0800 0000 1920 0014 5399")).isTrue()
        }

        @Test
        fun `validates correct German IBAN`() {
            assertThat(Iban.isValid("DE89370400440532013000")).isTrue()
        }

        @Test
        fun `validates correct British IBAN`() {
            assertThat(Iban.isValid("GB29 NWBK 6016 1331 9268 19")).isTrue()
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "",
                "CZ",
                "CZ00",
                "XX123456789012345",
                "1234567890123456789",
                "CZ65 0800 0000 1920 0014 5398", // wrong check digit
            ],
        )
        fun `rejects invalid IBANs`(input: String) {
            assertThat(Iban.isValid(input)).isFalse()
        }

        @Test
        fun `rejects too short IBAN`() {
            assertThat(Iban.isValid("CZ6508000000")).isFalse()
        }

        @Test
        fun `rejects too long IBAN`() {
            assertThat(Iban.isValid("CZ6508000000192000145399999999999999999")).isFalse()
        }
    }

    @Nested
    inner class Creation {

        @Test
        fun `creates IBAN from valid string`() {
            val iban = Iban.of("CZ65 0800 0000 1920 0014 5399")
            assertThat(iban.value).isEqualTo("CZ6508000000192000145399")
        }

        @Test
        fun `normalizes lowercase to uppercase`() {
            val iban = Iban.of("de89370400440532013000")
            assertThat(iban.value).isEqualTo("DE89370400440532013000")
        }

        @Test
        fun `strips spaces`() {
            val iban = Iban.of("GB29 NWBK 6016 1331 9268 19")
            assertThat(iban.value).doesNotContain(" ")
        }

        @Test
        fun `rejects invalid IBAN on construction`() {
            assertThatThrownBy { Iban("INVALID") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Invalid IBAN")
        }
    }

    @Nested
    inner class Properties {

        @Test
        fun `extracts country code`() {
            val iban = Iban.of("CZ6508000000192000145399")
            assertThat(iban.countryCode).isEqualTo("CZ")
        }

        @Test
        fun `extracts check digits`() {
            val iban = Iban.of("CZ6508000000192000145399")
            assertThat(iban.checkDigits).isEqualTo("65")
        }

        @Test
        fun `extracts BBAN`() {
            val iban = Iban.of("CZ6508000000192000145399")
            assertThat(iban.bban).isEqualTo("08000000192000145399")
        }
    }

    @Nested
    inner class Formatting {

        @Test
        fun `formats in 4-character groups`() {
            val iban = Iban.of("CZ6508000000192000145399")
            assertThat(iban.formatted()).isEqualTo("CZ65 0800 0000 1920 0014 5399")
        }

        @Test
        fun `toString returns raw value`() {
            val iban = Iban.of("DE89370400440532013000")
            assertThat(iban.toString()).isEqualTo("DE89370400440532013000")
        }
    }
}
