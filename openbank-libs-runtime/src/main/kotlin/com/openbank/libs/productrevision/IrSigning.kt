// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A detached signature over a product revision's `revisionHash` (ADR-0352 §4). The hash is the
 * SHA-256 hex of the canonical IR (`ProductIrCanonical.revisionHash`); the signature binds exactly
 * that string — never the JSON bytes, which may be re-serialised, and never the catalog row.
 *
 * [algorithm] names the scheme so a verifier refuses a signature it cannot interpret instead of
 * guessing; [keyId] names the key so rotation (ADR-0172) can keep verifying older revisions.
 */
data class IrSignature(val keyId: String, val algorithm: String, val signature: String) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(algorithm.isNotBlank()) { "algorithm must not be blank" }
        require(signature.isNotBlank()) { "signature must not be blank" }
    }

    override fun toString(): String = "IrSignature(keyId=$keyId, algorithm=$algorithm)"
}

/**
 * The publish path's signing capability (ADR-0352 §4, §7). Only product-catalog's publish of an
 * IR-bearing revision may hold an implementation wired to a real key; a developer, an agent or a
 * service account is never granted the Transit `sign` capability.
 */
fun interface IrSignerPort {
    fun sign(revisionHash: String): IrSignature
}

/** The consumer side: verifies a detached signature before a revision is ever evaluated. */
fun interface IrSignatureVerifierPort {
    /** `true` only when [signature] is a valid signature over [revisionHash] under a key this verifier trusts. */
    fun verify(revisionHash: String, signature: IrSignature): Boolean
}

/** Any failure to produce or check a signature. The message never carries key material or the signature. */
class IrSigningException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * HMAC-SHA256 signer/verifier for development and tests (ADR-0352 §4 names OpenBao Transit for a
 * deployment; this adapter exists so a laptop and a `@QuarkusTest` publish signed revisions with
 * no OpenBao). A shared secret proves nothing about WHO signed — anyone who can verify can also
 * sign — so a deployment must select [OpenBaoTransitIrSigner] instead; the catalog logs a boot-time
 * warning while this adapter is active.
 */
class HmacIrSigner(secret: ByteArray, private val keyId: String) :
    IrSignerPort,
    IrSignatureVerifierPort {

    private val key = SecretKeySpec(secret.copyOf(), MAC_ALGORITHM)

    init {
        require(secret.size >= MIN_SECRET_BYTES) { "HMAC secret must be at least $MIN_SECRET_BYTES bytes" }
        require(keyId.isNotBlank()) { "keyId must not be blank" }
    }

    override fun sign(revisionHash: String): IrSignature {
        requireHash(revisionHash)
        return IrSignature(keyId, ALGORITHM, Base64.getEncoder().encodeToString(mac(revisionHash)))
    }

    override fun verify(revisionHash: String, signature: IrSignature): Boolean {
        if (signature.algorithm != ALGORITHM || signature.keyId != keyId) return false
        if (!HASH_PATTERN.matches(revisionHash)) return false
        val presented = try {
            Base64.getDecoder().decode(signature.signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(presented, mac(revisionHash))
    }

    private fun mac(revisionHash: String): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).apply { init(key) }.doFinal(revisionHash.toByteArray(Charsets.US_ASCII))

    companion object {
        const val ALGORITHM = "hmac-sha256"
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 16
    }
}

internal val HASH_PATTERN = Regex("^[0-9a-f]{64}$")

internal fun requireHash(revisionHash: String) {
    if (!HASH_PATTERN.matches(revisionHash)) {
        throw IrSigningException("revisionHash must be 64 lower-case hex characters")
    }
}
