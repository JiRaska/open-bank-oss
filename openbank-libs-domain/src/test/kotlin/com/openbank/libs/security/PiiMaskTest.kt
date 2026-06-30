// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PiiMaskTest {

    @ParameterizedTest
    @CsvSource(
        "john.doe@example.com, j******e@example.com",
        "a@b.com,               *@b.com",
        "ab@b.com,              **@b.com",
        "abc@b.com,             a*c@b.com",
    )
    fun `email masks local part keeping first and last char`(input: String, expected: String) {
        assertThat(PiiMask.email(input)).isEqualTo(expected)
    }

    @Test
    fun `email returns empty for null or blank input`() {
        assertThat(PiiMask.email(null)).isEmpty()
        assertThat(PiiMask.email("  ")).isEmpty()
    }

    @Test
    fun `email returns triple-star when no at sign`() {
        assertThat(PiiMask.email("notanemail")).isEqualTo("***")
    }

    @Test
    fun `iban keeps first 4 and last 4 chars`() {
        assertThat(PiiMask.iban("CZ6508000000192000145399"))
            .isEqualTo("CZ65****************5399")
    }

    @Test
    fun `iban strips spaces before masking`() {
        assertThat(PiiMask.iban("CZ65 0800 0000 1920 0014 5399"))
            .isEqualTo("CZ65****************5399")
    }

    @Test
    fun `pan masks 16-digit card number to first 4 and last 4`() {
        assertThat(PiiMask.pan("4532015112830366")).isEqualTo("4532********0366")
    }

    @Test
    fun `pan handles dashes and spaces`() {
        assertThat(PiiMask.pan("4532-0151-1283-0366")).isEqualTo("4532********0366")
    }

    @Test
    fun `phone keeps country code and last 4 digits`() {
        assertThat(PiiMask.phone("+420123456789")).isEqualTo("+420*****6789")
    }

    @Test
    fun `name collapses words to initials`() {
        assertThat(PiiMask.name("Jiří Raška")).isEqualTo("J. R.")
        assertThat(PiiMask.name("Anna Marie Kovářová")).isEqualTo("A. M. K.")
    }

    @Test
    fun `national id keeps the 6-char date portion`() {
        assertThat(PiiMask.nationalId("8501010987")).isEqualTo("850101****")
        assertThat(PiiMask.nationalId("850101/0987")).isEqualTo("850101****")
    }

    @Test
    fun `full replaces every char with star`() {
        assertThat(PiiMask.full("secret")).isEqualTo("******")
    }

    @Test
    fun `apply dispatches to the right strategy`() {
        assertThat(PiiMask.apply(MaskStrategy.EMAIL, "x@y.z")).isEqualTo("*@y.z")
        assertThat(PiiMask.apply(MaskStrategy.NONE, "leave-me")).isEqualTo("leave-me")
    }
}
