// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class CanonicalJsonTest {

    @Test
    fun `keys sort by code point - a supplementary character sorts after U+E000-U+FFFF`() {
        val emoji = "😀" // U+1F600, UTF-16 high surrogate 0xD83D < 0xE000
        val privateUse = ""
        val fffd = "�"
        assertThat(CanonicalJson.write(mapOf(emoji to 1, fffd to 2, privateUse to 3)))
            .isEqualTo("{\"$privateUse\":3,\"$fffd\":2,\"$emoji\":1}")
    }

    @Test
    fun `a decimal keeps its scale - 12,50 stays 12,50`() {
        assertThat(CanonicalJson.write(mapOf("amount" to BigDecimal("12.50")))).isEqualTo("""{"amount":12.50}""")
        assertThat(CanonicalJson.write(BigDecimal("1E+3"))).isEqualTo("1000")
    }

    @Test
    fun `non-integral binary floats are refused`() {
        assertThatThrownBy { CanonicalJson.write(12.5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(0.1f) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CanonicalJson.write(3.0)).isEqualTo("3")
    }

    @Test
    fun `integers beyond 2^53 are refused, the boundary is accepted`() {
        val max = 9_007_199_254_740_992L // 2^53
        assertThat(CanonicalJson.write(max)).isEqualTo(max.toString())
        assertThat(CanonicalJson.write(-max)).isEqualTo((-max).toString())
        assertThatThrownBy { CanonicalJson.write(max + 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(Long.MAX_VALUE) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(BigInteger.TEN.pow(20)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(1e300) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-String map keys are refused - 1 and quoted 1 would collide`() {
        assertThatThrownBy { CanonicalJson.write(mapOf(1 to "a", "1" to "b")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("keys must be String")
    }
}
