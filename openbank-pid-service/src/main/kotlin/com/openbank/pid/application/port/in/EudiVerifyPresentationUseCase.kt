// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.port.`in`

import com.openbank.pid.domain.model.PidClaims

/**
 * Inbound port (ADR-0094): verify an EUDI wallet presentation and resolve the identity against pid.
 * The verified PID subject identifier is the tier-0 deterministic dedup key, fed into the existing
 * three-tier resolver ([ResolveIdentityUseCase]) — EUDI is a new front door, not a separate authority.
 */
interface EudiVerifyPresentationUseCase {
    suspend fun verifyAndResolve(command: VerifyPresentationCommand): EudiResolutionResult

    /** Verify an ISO 18013-5 mdoc (CBOR/COSE) PID and resolve it — the mdoc-format sibling of [verifyAndResolve]. */
    suspend fun verifyAndResolveMdoc(mdocBase64Url: String): EudiResolutionResult
}

data class VerifyPresentationCommand(val vpToken: String, val nonce: String? = null, val audience: String? = null)

/** The verified PID claims plus the resolution decision they produced. */
data class EudiResolutionResult(val claims: PidClaims, val resolution: ResolutionResult)
