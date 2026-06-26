// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BlindIndexTest {

    private val pepper = "pepper-v1".toByteArray()

    @Test
    fun `is deterministic for the same pepper and value`() {
        assertThat(BlindIndex.compute(pepper, "7605060342"))
            .isEqualTo(BlindIndex.compute(pepper, "7605060342"))
    }

    @Test
    fun `produces a 64-char lowercase hex digest`() {
        val idx = BlindIndex.compute(pepper, "7605060342")
        assertThat(idx).hasSize(64)
        assertThat(idx).matches("[0-9a-f]{64}")
    }

    @Test
    fun `different values produce different indexes`() {
        assertThat(BlindIndex.compute(pepper, "7605060342"))
            .isNotEqualTo(BlindIndex.compute(pepper, "8555230453"))
    }

    @Test
    fun `different peppers produce different indexes for the same value`() {
        assertThat(BlindIndex.compute("pepper-v1".toByteArray(), "7605060342"))
            .isNotEqualTo(BlindIndex.compute("pepper-v2".toByteArray(), "7605060342"))
    }

    @Test
    fun `matches a known HMAC-SHA256 vector`() {
        // RFC-style sanity vector: HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
        val idx = BlindIndex.compute("key".toByteArray(), "The quick brown fox jumps over the lazy dog")
        assertThat(idx).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
    }

    @Test
    fun `rejects an empty pepper`() {
        assertThatThrownBy { BlindIndex.compute(ByteArray(0), "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
