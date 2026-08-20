// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.openbank.audit.application.port.out.AnchorSigner
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import software.amazon.awssdk.services.kms.KmsClient
import java.util.Optional

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
        // Optional<String>, NOT a plain String with defaultValue = "": application.yaml binds
        // ${AUDIT_ANCHOR_KMS_KEY_ID:}, which DEFINES the property as the empty string, and SmallRye's
        // converter treats an empty value as no value at all. A non-optional String therefore fails
        // injection with SRCFG00040 before the method body runs, so the whole deployment aborts and
        // the `require` below was dead code that could never report the missing key (#5844 shipped
        // this way and left `main` red — no hmac deployment could boot either, since the kms branch
        // is not even reached).
        @ConfigProperty(name = "openbank.audit.anchor.kms-key-id") kmsKeyId: Optional<String>,
    ): AnchorSigner = select(signer, signingKey, kmsKeyId) { KmsClient.builder().build() }

    internal fun select(
        signer: String,
        signingKey: String,
        kmsKeyId: Optional<String>,
        kmsClient: () -> KmsClient,
    ): AnchorSigner = when (signer.trim().lowercase()) {
        "hmac" -> LocalHmacAnchorSigner(signingKey)
        "kms" -> {
            val keyId = kmsKeyId.orElse("").trim()
            require(keyId.isNotEmpty()) {
                "openbank.audit.anchor.kms-key-id is required when signer=kms"
            }
            AwsKmsAnchorSigner(kmsClient(), keyId)
        }
        else -> error("Unsupported openbank.audit.anchor.signer '$signer' (use hmac or kms)")
    }
}
