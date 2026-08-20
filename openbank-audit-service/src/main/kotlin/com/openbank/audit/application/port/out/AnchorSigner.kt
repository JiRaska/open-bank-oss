// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application.port.out

/**
 * Signs an audit-anchor digest with an external key (ADR-0031 D5) so a checkpoint stays
 * verifiable even after a wholesale rewrite of `audit_entries`.
 *
 * The default in-cluster adapter is HMAC-SHA256 (key held outside the audit DB). Production
 * overrides it with an AWS KMS / cosign-keyed adapter — the same KMS key already used for
 * release-evidence signing (ADR-0029/0030) — wired as a follow-up.
 */
interface AnchorSigner {
    /** Stable identifier of the signing key, recorded with every anchor for verification. */
    val keyId: String

    /** Returns a base64-encoded signature over [digest]. */
    fun sign(digest: ByteArray): String

    /** True when [signature] is a valid signature of [digest] under this signer's key. */
    fun verify(digest: ByteArray, signature: String): Boolean

    /** PEM-encoded public key for offline verification, or null for symmetric development signers. */
    fun verificationKeyPem(): String? = null
}
