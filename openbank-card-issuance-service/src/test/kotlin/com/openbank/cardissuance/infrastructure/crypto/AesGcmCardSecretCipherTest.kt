// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Optional

class AesGcmCardSecretCipherTest {

    // Encoded here rather than pasted as base64: a committed key-shaped literal is what the repo's
    // secret scan exists to reject, and neither a scanner nor a reader can tell "only a test key"
    // from the real thing. These protect nothing and never leave this file.
    private val key = Base64.getEncoder().encodeToString("openbank-test-only-aes-key-32byt".toByteArray())
    private val otherKey = Base64.getEncoder().encodeToString("another-test-only-aes-key-32byte".toByteArray())

    private val cipher = AesGcmCardSecretCipher(Optional.of(key), false)

    @Test fun `round trips a PAN`() {
        val pan = "4111111234567893"

        assertThat(cipher.decrypt(cipher.encrypt(pan))).isEqualTo(pan)
    }

    // A fresh IV per value is mandatory for GCM — reuse under one key is a catastrophic break, not
    // a weakness. Encrypting the same value twice must therefore never produce the same ciphertext.
    @Test fun `uses a fresh IV per value`() {
        val pan = "4111111234567893"

        val first = cipher.encrypt(pan)
        val second = cipher.encrypt(pan)

        assertThat(first).isNotEqualTo(second)
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second))
    }

    @Test fun `ciphertext is base64 with the 12-byte IV prepended`() {
        val sealed = Base64.getDecoder().decode(cipher.encrypt("123"))

        // 12-byte IV + 3-byte payload + 16-byte GCM tag
        assertThat(sealed).hasSize(12 + 3 + 16)
    }

    @Test fun `a tampered ciphertext does not decrypt`() {
        val sealed = Base64.getDecoder().decode(cipher.encrypt("4111111234567893"))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        assertThatThrownBy { cipher.decrypt(Base64.getEncoder().encodeToString(sealed)) }
            .isInstanceOf(Exception::class.java)
    }

    @Test fun `a different key does not decrypt`() {
        val sealed = cipher.encrypt("4111111234567893")

        assertThatThrownBy { AesGcmCardSecretCipher(Optional.of(otherKey), false).decrypt(sealed) }
            .isInstanceOf(Exception::class.java)
    }

    // Fail fast: the two silent alternatives (plaintext storage, or no credential at all) are both
    // worse than refusing to start.
    @Test fun `refuses to start without a key`() {
        assertThatThrownBy { AesGcmCardSecretCipher(Optional.empty(), false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("openbank.card.pan-encryption-key is not set")
    }

    @Test fun `refuses to start with a key of the wrong length`() {
        val tooShort = Base64.getEncoder().encodeToString("16-byte-key-here".toByteArray())

        assertThatThrownBy { AesGcmCardSecretCipher(Optional.of(tooShort), false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must decode to exactly 32 bytes")
    }

    @Test fun `refuses to start with a non base64 key`() {
        assertThatThrownBy { AesGcmCardSecretCipher(Optional.of("not base64 at all !!"), false) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not valid base64")
    }

    @Test fun `mints an ephemeral key instead of failing when the profile is dev or test`() {
        // The point of the dev/test path: no key configured, no committed key, still boots.
        val dev = AesGcmCardSecretCipher(Optional.empty(), true)

        assertThat(dev.decrypt(dev.encrypt("4111111111111111"))).isEqualTo("4111111111111111")
    }

    @Test fun `two dev boots do not share a key`() {
        // Ephemeral means ephemeral — this is why the dev profile cannot be used to store
        // anything that has to survive a restart.
        val sealed = AesGcmCardSecretCipher(Optional.empty(), true).encrypt("4111111111111111")

        assertThatThrownBy { AesGcmCardSecretCipher(Optional.empty(), true).decrypt(sealed) }
            .isInstanceOf(Exception::class.java)
    }
}
