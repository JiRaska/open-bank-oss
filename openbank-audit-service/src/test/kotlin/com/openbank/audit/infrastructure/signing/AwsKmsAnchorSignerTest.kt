// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse
import software.amazon.awssdk.services.kms.model.SignRequest
import software.amazon.awssdk.services.kms.model.SignResponse
import software.amazon.awssdk.services.kms.model.VerifyRequest
import software.amazon.awssdk.services.kms.model.VerifyResponse

class AwsKmsAnchorSignerTest {

    private val kms = mockk<KmsClient>()
    private val signer = AwsKmsAnchorSigner(kms, "alias/openbank-audit-anchor")
    private val digest = "digest".toByteArray()

    @Test
    fun `sign delegates the raw digest to the configured KMS key`() {
        val request = slot<SignRequest>()
        every { kms.sign(capture(request)) } returns SignResponse.builder()
            .signature(SdkBytes.fromUtf8String("signature"))
            .build()

        assertThat(signer.sign(digest)).isEqualTo("c2lnbmF0dXJl")
        assertThat(request.captured.keyId()).isEqualTo("alias/openbank-audit-anchor")
        assertThat(request.captured.message().asByteArray()).isEqualTo(digest)
        assertThat(request.captured.signingAlgorithmAsString()).isEqualTo("ECDSA_SHA_256")
    }

    @Test
    fun `verify uses KMS and rejects malformed signatures without a remote call`() {
        val request = slot<VerifyRequest>()
        every { kms.verify(capture(request)) } returns VerifyResponse.builder().signatureValid(true).build()

        assertThat(signer.verify(digest, "c2lnbmF0dXJl")).isTrue()
        assertThat(request.captured.keyId()).isEqualTo("alias/openbank-audit-anchor")
        assertThat(request.captured.message().asByteArray()).isEqualTo(digest)
        assertThat(signer.verify(digest, "not-base64!")).isFalse()
    }

    @Test
    fun `exports KMS public key as PEM for offline verification`() {
        val request = slot<GetPublicKeyRequest>()
        every { kms.getPublicKey(capture(request)) } returns GetPublicKeyResponse.builder()
            .publicKey(SdkBytes.fromUtf8String("public-key"))
            .build()

        assertThat(signer.verificationKeyPem())
            .isEqualTo("-----BEGIN PUBLIC KEY-----\ncHVibGljLWtleQ==\n-----END PUBLIC KEY-----\n")
        assertThat(request.captured.keyId()).isEqualTo("alias/openbank-audit-anchor")
    }
}
