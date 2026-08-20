// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifies a stored KMS ECDSA signature without depending on the current KMS alias or IAM grant. */
object PublicKeyAnchorVerifier {
    fun verify(digest: ByteArray, signature: String, publicKeyPem: String): Boolean = runCatching {
        val encodedKey = publicKeyPem.replace(PEM_ARMOR, "")
        val publicKey = KeyFactory.getInstance(
            "EC",
        ).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(digest)
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    private val PEM_ARMOR = Regex("-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\\s")
}
