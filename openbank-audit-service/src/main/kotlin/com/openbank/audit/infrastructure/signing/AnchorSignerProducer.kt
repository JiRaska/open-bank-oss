// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.openbank.audit.application.port.out.AnchorSigner
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.services.kms.KmsClient

/** Selects the local development signer or the dedicated asymmetric KMS signer. */
@Singleton
class AnchorSignerProducer {

    @Produces
    fun signer(
        @ConfigProperty(name = "openbank.audit.anchor.signer", defaultValue = "hmac") signer: String,
        @ConfigProperty(
            name = "openbank.audit.anchor.signing-key",
            defaultValue = "CHANGE_ME_LOCAL_DEV_ONLY",
        ) signingKey: String,
        @ConfigProperty(name = "openbank.audit.anchor.kms-key-id", defaultValue = "") kmsKeyId: String,
    ): AnchorSigner = when (signer.trim().lowercase()) {
        "hmac" -> LocalHmacAnchorSigner(signingKey)
        "kms" -> {
            require(kmsKeyId.isNotBlank()) {
                "openbank.audit.anchor.kms-key-id is required when signer=kms"
            }
            AwsKmsAnchorSigner(KmsClient.builder().build(), kmsKeyId)
        }
        else -> error("Unsupported openbank.audit.anchor.signer '$signer' (use hmac or kms)")
    }
}
