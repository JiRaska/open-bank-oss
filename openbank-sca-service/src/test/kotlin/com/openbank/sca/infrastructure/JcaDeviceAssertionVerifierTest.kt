// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure

import com.openbank.sca.domain.model.SignatureAlgorithm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JcaDeviceAssertionVerifierTest {

    private val verifier = JcaDeviceAssertionVerifier()

    @Test
    fun `accepts a valid ES256 signature over the payload`() {
        val keyPair = es256KeyPair()
        val payload = "challenge|APPROVED|100.00|EUR|CZ65...|ref".toByteArray()
        val signature = signEs256(keyPair, payload)

        val ok = verifier.verify(spkiB64(keyPair), SignatureAlgorithm.ES256, payload, signature)

        assertThat(ok).isTrue()
    }

    @Test
    fun `rejects a signature when the payload was tampered (dynamic linking)`() {
        val keyPair = es256KeyPair()
        val signed = "challenge|APPROVED|100.00|EUR|CZ65...|ref".toByteArray()
        val signature = signEs256(keyPair, signed)
        // Attacker keeps the signature but changes the amount — must not verify.
        val tampered = "challenge|APPROVED|999.00|EUR|CZ65...|ref".toByteArray()

        val ok = verifier.verify(spkiB64(keyPair), SignatureAlgorithm.ES256, tampered, signature)

        assertThat(ok).isFalse()
    }

    @Test
    fun `rejects a signature from a different key`() {
        val signingKey = es256KeyPair()
        val otherKey = es256KeyPair()
        val payload = "challenge|APPROVED|1|EUR||".toByteArray()
        val signature = signEs256(signingKey, payload)

        val ok = verifier.verify(spkiB64(otherKey), SignatureAlgorithm.ES256, payload, signature)

        assertThat(ok).isFalse()
    }

    @Test
    fun `fails closed on a malformed public key`() {
        val ok = verifier.verify("not-base64-spki", SignatureAlgorithm.ES256, "x".toByteArray(), "AAAA")
        assertThat(ok).isFalse()
    }

    private fun es256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun signEs256(keyPair: KeyPair, payload: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(payload)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun spkiB64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)
}
