// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application.port.out

/**
 * Signs an audit-anchor digest with an external key (ADR-0031 D5) so a checkpoint stays
 * verifiable even after a wholesale rewrite of `audit_entries`.
 *
 * The default in-cluster adapter is HMAC-SHA256 (key held outside the audit DB). Production
 * overrides it with a dedicated AWS KMS adapter. The KMS key is deliberately separate from
 * release-evidence signing keys, so compromise or rotation has a bounded purpose.
 */
interface AnchorSigner {
    /** Stable identifier of the signing key, recorded with every anchor for verification. */
    val keyId: String

    /** Returns a base64-encoded signature and the immutable key identifier KMS actually used. */
    fun sign(digest: ByteArray): AnchorSignature

    /**
     * True when [signature] is valid under the persisted [keyId], false when invalid, or null
     * when this runtime cannot verify that historical key (for example a legacy HMAC anchor).
     */
    fun verify(digest: ByteArray, signature: String, keyId: String): Boolean?

    /** PEM-encoded public key for [keyId], or null for symmetric development signers. */
    fun verificationKeyPem(keyId: String): String? = null
}

data class AnchorSignature(val value: String, val keyId: String)
