// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application.port.out

/**
 * Signs an audit-anchor digest with an external key (ADR-0031 D5) so a checkpoint stays
 * verifiable even after a wholesale rewrite of `audit_entries`.
 *
 * **Asymmetric on purpose.** The predecessor adapter was HMAC-SHA256: verification required the
 * signing secret, so the set of parties able to verify an anchor was exactly the set able to forge
 * one. An audit-integrity control with that shape is not independently verifiable, which is why
 * ADR-0031 D5 stayed partial. The signer therefore holds only a *private* key it never exports,
 * and verification is done by [com.openbank.audit.domain.crypto.AnchorSignatureVerifier] from
 * public material alone.
 *
 * **Fail closed.** [sign] must throw [AnchorSigningException] when the key is unavailable,
 * unusable, or the signing backend rejects the request. It must never return a signature made
 * with a locally-improvised key, and the caller must never persist an anchor without one — an
 * unsigned checkpoint that is stored anyway is an anchor that reads as evidence and is not.
 */
interface AnchorSigner {
    /**
     * Stable identifier of the signing key, recorded with every anchor so a verifier can resolve
     * the matching public key at verification time.
     */
    val keyId: String

    /** Returns a base64-encoded signature over [digest], or throws [AnchorSigningException]. */
    suspend fun sign(digest: ByteArray): String
}

/**
 * Resolves the **public** material for a signing key id, so anchors can be verified without the
 * signer. Separate from [AnchorSigner] because verification is a different trust domain: a
 * verifier needs this port and nothing else.
 */
interface AnchorPublicKeyResolver {
    /**
     * PEM-encoded SPKI public key for [keyId], or `null` when no public material is available for
     * it (an unknown key, or a legacy symmetric key that has none). `null` means *unverifiable* —
     * callers must report it as such and never as a successful verification.
     */
    suspend fun publicKeyPem(keyId: String): String?
}

/** Raised when an anchor cannot be signed. Always fatal to the capture — never swallowed. */
class AnchorSigningException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
