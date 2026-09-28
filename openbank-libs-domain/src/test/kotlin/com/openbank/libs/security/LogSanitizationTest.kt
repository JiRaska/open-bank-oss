// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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

    @Test
    fun `a lone LF is replaced with an underscore`() {
        assertThat("line1\nline2".sanitizeForLog()).isEqualTo("line1_line2")
    }

    @Test
    fun `a CR LF pair becomes two underscores, one per character`() {
        // Matches the original per-file idiom's behavior exactly: .replace('\n','_').replace('\r','_')
        // replaces each control character independently, so CRLF is not collapsed to one underscore.
        assertThat("line1\r\nline2".sanitizeForLog()).isEqualTo("line1__line2")
    }

    @Test
    fun `a lone CR is replaced with an underscore`() {
        assertThat("line1\rline2".sanitizeForLog()).isEqualTo("line1_line2")
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
