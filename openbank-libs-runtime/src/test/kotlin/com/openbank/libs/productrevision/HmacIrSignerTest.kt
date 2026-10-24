// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class HmacIrSignerTest {

    private val secret = "0123456789abcdef0123456789abcdef".toByteArray()
    private val signer = HmacIrSigner(secret, "dev-hmac")
    private val hash = "a".repeat(64)
    private val identity =
        RevisionIdentity("SAV-FIXED", UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 1, 1, hash)

    @Test
    fun `a signature verifies under the same secret and key id`() {
        val sig = signer.sign(identity)
        assertThat(sig.algorithm).isEqualTo(HmacIrSigner.ALGORITHM)
        assertThat(sig.keyId).isEqualTo("dev-hmac")
        assertThat(signer.verify(identity, sig)).isTrue()
        assertThat(signer.sign(identity)).isEqualTo(sig)
    }

    @Test
    fun `a different hash, secret, key id, algorithm or tampered bytes do not verify`() {
        val sig = signer.sign(identity)
        assertThat(signer.verify(identity.copy(revisionHash = "b".repeat(64)), sig)).isFalse()
        assertThat(
            HmacIrSigner("fedcba9876543210fedcba9876543210".toByteArray(), "dev-hmac").verify(identity, sig),
        ).isFalse()
        assertThat(signer.verify(identity, sig.copy(keyId = "other"))).isFalse()
        assertThat(signer.verify(identity, sig.copy(algorithm = "openbao-transit"))).isFalse()
        assertThat(signer.verify(identity, sig.copy(signature = "not base64!"))).isFalse()
        val flipped = sig.signature.toCharArray().also { it[0] = if (it[0] == 'A') 'B' else 'A' }.concatToString()
        assertThat(signer.verify(identity, sig.copy(signature = flipped))).isFalse()
    }

    @Test
    fun `an identity cannot carry anything that is not a revision hash, and a short secret is refused`() {
        assertThatThrownBy {
            identity.copy(revisionHash = "not-a-hash")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HmacIrSigner("short".toByteArray(), "k")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(signer.sign(identity).toString()).doesNotContain(signer.sign(identity).signature)
    }
}
