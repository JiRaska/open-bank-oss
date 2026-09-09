// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Pbkdf2DisclosureSecretCodecTest {
    private val codec = Pbkdf2DisclosureSecretCodec()

    @Test
    fun `opaque tokens are high entropy URL-safe and stored only as digests`() {
        val first = codec.newOpaqueToken()
        val second = codec.newOpaqueToken()

        assertThat(first).hasSize(43).matches("^[A-Za-z0-9_-]+$")
        assertThat(second).isNotEqualTo(first)
        assertThat(codec.hashOpaqueToken(first)).hasSize(64).doesNotContain(first)
    }

    @Test
    fun `otp digest is salted slow and constant-result verified`() {
        val digest = codec.hashOtp("123456")

        assertThat(digest.salt).hasSize(32)
        assertThat(digest.digest).hasSize(64)
        assertThat(codec.verifyOtp("123456", digest.salt, digest.digest)).isTrue()
        assertThat(codec.verifyOtp("123457", digest.salt, digest.digest)).isFalse()
        assertThat(codec.verifyOtp("bad", digest.salt, digest.digest)).isFalse()
    }
}
