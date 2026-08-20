// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PublicKeyAnchorVerifierTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val digest = "digest-to-attest".toByteArray()

    @Test
    fun `verifies a captured EC public key without KMS`() {
        assertThat(PublicKeyAnchorVerifier.verify(digest, signedDigest(), publicKeyPem())).isTrue()
    }

    @Test
    fun `rejects a modified digest or signature`() {
        assertThat(PublicKeyAnchorVerifier.verify("rewritten".toByteArray(), signedDigest(), publicKeyPem())).isFalse()
        assertThat(PublicKeyAnchorVerifier.verify(digest, "not-base64", publicKeyPem())).isFalse()
    }

    private fun signedDigest(): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(digest)
        Base64.getEncoder().encodeToString(sign())
    }

    private fun publicKeyPem(): String = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
        "\n-----END PUBLIC KEY-----\n"
}
