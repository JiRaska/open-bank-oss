// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.openbank.audit.application.port.out.AnchorSigner
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Default [AnchorSigner]: HMAC-SHA256 with a key supplied out-of-band (env / Vault), never stored
 * in the audit database. This already defeats the in-DB rewrite threat. A production deployment
 * replaces this bean (it is a [DefaultBean]) with a KMS/cosign-keyed asymmetric signer for full
 * third-party verifiability — see [AnchorSigner].
 */
class LocalHmacAnchorSigner(private val signingKey: String) : AnchorSigner {

    override val keyId: String = "local-hmac-sha256"

    override fun sign(digest: ByteArray): String {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), ALGO))
        return Base64.getEncoder().encodeToString(mac.doFinal(digest))
    }

    override fun verify(digest: ByteArray, signature: String): Boolean = runCatching {
        // Constant-time comparison: never branch on signature content.
        MessageDigest.isEqual(
            sign(digest).toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
    }.getOrDefault(false)

    private companion object {
        const val ALGO = "HmacSHA256"
    }
}
