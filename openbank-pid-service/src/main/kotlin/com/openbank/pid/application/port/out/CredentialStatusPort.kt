// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

/**
 * Checks the revocation status of a presented credential against a Token Status List (ADR-0094).
 * Implemented for THIS issuer's own list; a presentation referencing a foreign status list is not
 * resolved here (out of scope) and is treated as not-revoked by the caller.
 */
interface CredentialStatusPort {
    /** True if the credential at [index] of the status list at [uri] is revoked by this issuer. */
    suspend fun isRevoked(uri: String, index: Long): Boolean
}
