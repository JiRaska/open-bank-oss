// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.openbank.audit.application.port.out.AnchorSigner
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest
import software.amazon.awssdk.services.kms.model.MessageType
import software.amazon.awssdk.services.kms.model.SignRequest
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec
import software.amazon.awssdk.services.kms.model.VerifyRequest
import java.util.Base64

/** Asymmetric audit-anchor signer backed by a dedicated AWS KMS ECC_NIST_P256 key. */
class AwsKmsAnchorSigner(private val kms: KmsClient, override val keyId: String) : AnchorSigner {

    override fun sign(digest: ByteArray): String = Base64.getEncoder().encodeToString(
        kms.sign(
            SignRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(digest))
                .messageType(MessageType.RAW)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build(),
        ).signature().asByteArray(),
    )

    override fun verify(digest: ByteArray, signature: String): Boolean = runCatching {
        kms.verify(
            VerifyRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(digest))
                .messageType(MessageType.RAW)
                .signature(SdkBytes.fromByteArray(Base64.getDecoder().decode(signature)))
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build(),
        ).signatureValid()
    }.getOrDefault(false)

    override fun verificationKeyPem(): String = kms.getPublicKey(
        GetPublicKeyRequest.builder().keyId(keyId).build(),
    ).publicKey().asByteArray().let { der ->
        val encoded = Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(der)
        "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
    }

    private companion object {
        const val PEM_LINE_LENGTH = 64
    }
}
