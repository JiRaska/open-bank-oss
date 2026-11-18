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

    @Test
    fun `rejects a mod-97-valid IBAN whose length does not match its country`() {
        // DE length (22) under a CZ country code; check digits recomputed so only length is wrong
        val bban = "370400440532013000"
        assertThat(Iban.isValid(withCheckDigits("CZ", bban))).isFalse()
    }

    @Test
    fun `rejects check digits outside 02 to 98`() {
        // 00 is congruent to 97 mod 97, so DE00 passes the checksum; only the range rule rejects it
        assertThat(Iban.isValid("DE97370400440000000060")).isTrue()
        assertThat(Iban.isValid("DE00370400440000000060")).isFalse()
    }

    @Test
    fun `returns false instead of throwing on punctuation in the BBAN`() {
        // a non-leading '-' made the old code throw NumberFormatException
        assertThat(Iban.isValid("DE8937040044-532013000")).isFalse()
    }

    @Test
    fun `rejects a country absent from the IBAN registry`() {
        // check digits recomputed for US so only the registry rule can reject it
        val bban = "370400440532013000"
        assertThat(Iban.isValid(withCheckDigits("US", bban))).isFalse()
    }

    @Test
    fun `rejects a BBAN that breaks its country's registry structure`() {
        // check digits valid, length valid; only the per-position class is wrong
        assertThat(Iban.isValid(withCheckDigits("GB", "123456987654321234"))).isFalse() // bank code must be 4 letters
        assertThat(Iban.isValid(withCheckDigits("CZ", "0800A00019200014539X"))).isFalse() // CZ BBAN is all digits
        assertThat(Iban.isValid(withCheckDigits("DE", "37040044053201300A"))).isFalse() // DE BBAN is all digits
    }

    /** Builds an IBAN with correct mod-97 check digits, so only the other rules can reject it. */
    private fun withCheckDigits(country: String, bban: String): String {
        val numeric = "${bban}${country}00".map { if (it.isDigit()) "$it" else "${it.code - 55}" }
        val check = 98 - numeric.joinToString("").toBigInteger().mod(97.toBigInteger()).toInt()
        return "%s%02d%s".format(country, check, bban)
    }
}
