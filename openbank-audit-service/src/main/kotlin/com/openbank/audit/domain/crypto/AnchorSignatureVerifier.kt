// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies an audit-anchor signature (ADR-0031 D5) from **public key material only**.
 *
 * This is the whole point of the asymmetric anchor signer. The predecessor was HMAC-SHA256, where
 * verification needed the signing secret — so anybody able to verify an anchor was equally able to
 * forge one, and "independently verifiable" was not a property the control ever had. Here the
 * signature is ECDSA over P-256 (`SHA256withECDSA`, ASN.1/DER encoded, base64 on the wire), the
 * same shape `cosign` produces for a HashiCorp-Vault/OpenBao `transit` ECDSA key, so a third party
 * holding nothing but the exported SPKI PEM can check an anchor with `cosign verify-blob` or with
 * this class.
 *
 * Pure and framework-free by construction (ADR-0002): it takes bytes and a PEM string, holds no
 * key, opens no connection, and therefore cannot accidentally acquire access to a private key.
 *
 * Every failure — malformed PEM, wrong key type, malformed signature, wrong digest — returns
 * `false`. It never throws and it never returns `true` for an input it could not actually check:
 * "unverifiable" is reported by the caller as its own state, never folded into success (the
 * `PushResult.skipped()` lesson).
 */
object AnchorSignatureVerifier {

    /** JCA name of the signature scheme; matches an OpenBao `ecdsa-p256` transit key. */
    const val ALGORITHM: String = "SHA256withECDSA"

    /** JCA key-factory algorithm for the public key material. */
    const val KEY_ALGORITHM: String = "EC"

    /**
     * True when [signatureBase64] is a valid [ALGORITHM] signature over [digest] under the public
     * key in [publicKeyPem] (an X.509/SPKI `-----BEGIN PUBLIC KEY-----` block).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun verify(digest: ByteArray, signatureBase64: String, publicKeyPem: String): Boolean = try {
        val key = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(decodePem(publicKeyPem)))
        Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(digest)
            verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    } catch (e: Exception) {
        false
    }

    /** Strips the PEM armour and decodes the base64 body. */
    private fun decodePem(pem: String): ByteArray = Base64.getMimeDecoder().decode(
        pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim(),
    )
}
