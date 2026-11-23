// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

/** The issuer may proceed only after a committed [FIRST_USE] response. */
enum class McpIssuerProofClaim {
    FIRST_USE,
    REPLAYED,
    UNAVAILABLE,
}

interface McpIssuerProofReplayStore {
    /**
     * [proofKey] is the canonical lower-case hex HMAC-SHA-256 of the deployment purpose, verified
     * realm, thumbprint and proof id, encoded with length prefixes. The caller derives it only
     * after proof and nonce verification. The key is invariant across account, operation and token.
     */
    suspend fun claim(proofKey: String): McpIssuerProofClaim

    /** Removes at most [limit] expired claims. A reviewed scheduler must call before activation. */
    suspend fun purgeExpired(limit: Int): Int
}
