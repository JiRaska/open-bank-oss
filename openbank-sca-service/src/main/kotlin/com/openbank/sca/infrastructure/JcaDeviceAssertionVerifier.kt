// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure

import com.openbank.sca.application.port.out.DeviceAssertionVerifier
import com.openbank.sca.domain.model.SignatureAlgorithm
import jakarta.enterprise.context.ApplicationScoped
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies device assertions with the JDK crypto provider (ADR-0021). ECDSA P-256 /
 * SHA-256 covers WebAuthn ES256 and Apple Secure Enclave; Ed25519 covers EdDSA
 * credentials. Public keys are X.509 SubjectPublicKeyInfo; ECDSA signatures are the
 * DER form WebAuthn/Secure Enclave emit, which `SHA256withECDSA` consumes directly.
 *
 * NOTE (production hardening): full WebAuthn/FIDO2 also parses the CBOR attestation
 * object and authenticator data and checks the attestation statement. Here we verify
 * the *assertion* (the cryptographic core that satisfies dynamic linking). Attestation
 * parsing is a follow-up; see ADR-0064/0065 device-attestation hook.
 */
@ApplicationScoped
class JcaDeviceAssertionVerifier : DeviceAssertionVerifier {

    // A malformed or unsupported assertion is a non-approval; do not log attacker-supplied material.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun verify(
        publicKeySpkiB64: String,
        algorithm: SignatureAlgorithm,
        payload: ByteArray,
        signatureB64: String,
    ): Boolean = try {
        val keyBytes = Base64.getDecoder().decode(publicKeySpkiB64)
        val sigBytes = Base64.getDecoder().decode(signatureB64)
        val (keyFactoryAlg, signatureAlg) = when (algorithm) {
            SignatureAlgorithm.ES256 -> "EC" to "SHA256withECDSA"
            SignatureAlgorithm.ED25519 -> "Ed25519" to "Ed25519"
        }
        val publicKey = KeyFactory.getInstance(keyFactoryAlg)
            .generatePublic(X509EncodedKeySpec(keyBytes))
        Signature.getInstance(signatureAlg).run {
            initVerify(publicKey)
            update(payload)
            verify(sigBytes)
        }
    } catch (e: Exception) {
        // Fail closed: any malformed key/signature is a non-approval, never a success.
        false
    }
}
