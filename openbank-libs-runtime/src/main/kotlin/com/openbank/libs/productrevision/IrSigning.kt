// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.openbank.libs.audit.CanonicalJson
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The identity a publish-path signature binds (ADR-0352 §4): which product, which catalog revision
 * (id and number), which IR version, and the canonical hash of its IR. A consumer's pin is one of
 * these, and [PinnedRevisionLoader] serves a revision only when every field equals the pin.
 *
 * The hash alone is not enough: it covers the IR (so `productCode` and `irVersion`), but two catalog
 * revisions with identical behaviour share one hash, and a hash says nothing about which revision row
 * a contract pinned. Signing the bare hash therefore let a valid signature for one revision be
 * presented under another revision's pin.
 *
 * [signingInput] is the bytes actually signed: RFC 8785-style canonical JSON of the five fields plus
 * a fixed domain tag, so the encoding is injective (no delimiter ambiguity) and a signature over this
 * envelope can never be confused with one over any other document, including a bare hash.
 */
data class RevisionIdentity(
    val productCode: String,
    val revisionId: UUID,
    val revisionNumber: Long,
    val irVersion: Int,
    val revisionHash: String,
) {
    init {
        require(productCode.isNotBlank()) { "productCode must not be blank" }
        require(revisionNumber > 0) { "revisionNumber must be positive" }
        require(irVersion > 0) { "irVersion must be positive" }
        require(HASH_PATTERN.matches(revisionHash)) { "revisionHash must be 64 lower-case hex characters" }
    }

    fun signingInput(): ByteArray = CanonicalJson.write(
        mapOf(
            "domain" to SIGNING_DOMAIN,
            "irVersion" to irVersion,
            "productCode" to productCode,
            "revisionHash" to revisionHash,
            "revisionId" to revisionId.toString(),
            "revisionNumber" to revisionNumber,
        ),
    ).toByteArray(Charsets.UTF_8)

    companion object {
        /** Versioned domain-separation tag; changing the envelope means a new tag, never a reinterpretation. */
        const val SIGNING_DOMAIN = "openbank.product-revision-signature.v1"
    }
}

/**
 * A detached signature over a product revision's [RevisionIdentity] envelope (ADR-0352 §4) — never
 * the JSON bytes, which may be re-serialised, and never the bare hash.
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
    fun sign(identity: RevisionIdentity): IrSignature
}

/** The consumer side: verifies a detached signature before a revision is ever evaluated. */
fun interface IrSignatureVerifierPort {
    /** `true` only when [signature] is a valid signature over [identity]'s envelope under a key this verifier trusts. */
    fun verify(identity: RevisionIdentity, signature: IrSignature): Boolean
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

    override fun sign(identity: RevisionIdentity): IrSignature =
        IrSignature(keyId, ALGORITHM, Base64.getEncoder().encodeToString(mac(identity)))

    override fun verify(identity: RevisionIdentity, signature: IrSignature): Boolean {
        if (signature.algorithm != ALGORITHM || signature.keyId != keyId) return false
        val presented = try {
            Base64.getDecoder().decode(signature.signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(presented, mac(identity))
    }

    private fun mac(identity: RevisionIdentity): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).apply { init(key) }.doFinal(identity.signingInput())

    companion object {
        const val ALGORITHM = "hmac-sha256"
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 16
    }
}

internal val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
