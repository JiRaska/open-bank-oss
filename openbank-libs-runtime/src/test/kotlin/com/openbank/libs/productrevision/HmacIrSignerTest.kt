// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HmacIrSignerTest {

    private val secret = "0123456789abcdef0123456789abcdef".toByteArray()
    private val signer = HmacIrSigner(secret, "dev-hmac")
    private val hash = "a".repeat(64)

    @Test
    fun `a signature verifies under the same secret and key id`() {
        val sig = signer.sign(hash)
        assertThat(sig.algorithm).isEqualTo(HmacIrSigner.ALGORITHM)
        assertThat(sig.keyId).isEqualTo("dev-hmac")
        assertThat(signer.verify(hash, sig)).isTrue()
        assertThat(signer.sign(hash)).isEqualTo(sig)
    }

    @Test
    fun `a different hash, secret, key id, algorithm or tampered bytes do not verify`() {
        val sig = signer.sign(hash)
        assertThat(signer.verify("b".repeat(64), sig)).isFalse()
        assertThat(
            HmacIrSigner("fedcba9876543210fedcba9876543210".toByteArray(), "dev-hmac").verify(hash, sig),
        ).isFalse()
        assertThat(signer.verify(hash, sig.copy(keyId = "other"))).isFalse()
        assertThat(signer.verify(hash, sig.copy(algorithm = "openbao-transit"))).isFalse()
        assertThat(signer.verify(hash, sig.copy(signature = "not base64!"))).isFalse()
        val flipped = sig.signature.toCharArray().also { it[0] = if (it[0] == 'A') 'B' else 'A' }.concatToString()
        assertThat(signer.verify(hash, sig.copy(signature = flipped))).isFalse()
    }

    @Test
    fun `refuses to sign anything that is not a revision hash and refuses a short secret`() {
        assertThatThrownBy { signer.sign("not-a-hash") }.isInstanceOf(IrSigningException::class.java)
        assertThatThrownBy {
            HmacIrSigner("short".toByteArray(), "k")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(signer.sign(hash).toString()).doesNotContain(signer.sign(hash).signature)
    }
}
