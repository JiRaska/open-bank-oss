// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LogSanitizationTest {

    @Test
    fun `null becomes the placeholder dash`() {
        val value: String? = null
        assertThat(value.sanitizeForLog()).isEqualTo("-")
    }

    @Test
    fun `blank string is left as-is, not replaced with the placeholder`() {
        assertThat("".sanitizeForLog()).isEqualTo("")
        assertThat("   ".sanitizeForLog()).isEqualTo("   ")
    }

    @Test
    fun `value with no CR or LF is unchanged`() {
        assertThat("perfectly-ordinary-value_123".sanitizeForLog()).isEqualTo("perfectly-ordinary-value_123")
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "line1\nline2, line1_line2",
            "line1\r\nline2, line1_line2",
            "line1\rline2, line1_line2",
        ],
    )
    fun `CR and LF are each replaced with an underscore`(input: String, expected: String) {
        assertThat(input.sanitizeForLog()).isEqualTo(expected)
    }

    @Test
    fun `forged log line attempt is neutralised into a single line`() {
        val attack = "alice\nERROR: fake audit entry injected by attacker"
        val sanitized = attack.sanitizeForLog()
        assertThat(sanitized).doesNotContain("\n").doesNotContain("\r")
        assertThat(sanitized).isEqualTo("alice_ERROR: fake audit entry injected by attacker")
    }

    @Test
    fun `repeated CR LF pairs are each collapsed independently`() {
        assertThat("a\r\n\r\nb".sanitizeForLog()).isEqualTo("a____b")
    }
}
