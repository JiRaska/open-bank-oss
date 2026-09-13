// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The dev/test token provider: its only real behaviour is the non-blank precondition. */
class StaticServiceTokenProviderTest {

    @Test
    fun `returns the configured token unchanged on every call`() {
        val provider = StaticServiceTokenProvider("abc.def.ghi")
        assertThat(provider.getToken()).isEqualTo("abc.def.ghi")
        assertThat(provider.getToken()).isEqualTo("abc.def.ghi")
    }

    @Test
    fun `refresh is a no-op and does not invalidate the token`() {
        val provider = StaticServiceTokenProvider("t")
        provider.refresh()
        assertThat(provider.getToken()).isEqualTo("t")
    }

    @Test
    fun `an empty token is rejected at construction, not at first use`() {
        assertThatThrownBy { StaticServiceTokenProvider("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("non-blank")
    }

    @Test
    fun `a whitespace-only token is blank and rejected too`() {
        assertThatThrownBy { StaticServiceTokenProvider("   \t\n") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `it satisfies the ServiceTokenProvider port`() {
        val port: ServiceTokenProvider = StaticServiceTokenProvider("x")
        assertThat(port.getToken()).isNotBlank()
    }
}
