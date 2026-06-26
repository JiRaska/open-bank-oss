// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.rest.dto

import com.openbank.pid.application.port.`in`.EudiResolutionResult
import com.openbank.pid.application.port.`in`.ResolutionResult
import com.openbank.pid.domain.model.PidClaims
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Request for `POST /api/v1/parties/eudi/verify-presentation`. */
data class VerifyPresentationRequest(
    /** The wallet's verifiable presentation — an SD-JWT VC: `<issuer-JWS>~<disclosure>...~[KB-JWT]`. */
    val vpToken: String,
    /** Optional anti-replay nonce; when supplied, a holder key-binding JWT is required and checked. */
    val nonce: String? = null,
    /** Optional expected audience (this RP); enforced against the key-binding JWT when supplied. */
    val audience: String? = null,
)

/** Request for `POST /api/v1/parties/eudi/verify-mdoc`. */
data class VerifyMdocRequest(
    /** The wallet's ISO 18013-5 mdoc — a base64url-encoded `IssuerSigned` CBOR map. */
    val mdoc: String,
)

/** The verified PID attributes returned to the (authenticated, M2M) caller. The subject id is masked. */
data class PidClaimsResponse(
    val subjectIdMasked: String,
    val givenName: String,
    val familyName: String,
    val birthDate: LocalDate,
    val birthPlace: String?,
    val nationalities: List<String>,
    val issuingCountry: String,
    val issuer: String,
    val levelOfAssurance: String,
)

/** Response for the verify-presentation endpoint: the resolution decision + the verified claims. */
data class VerifyPresentationResponse(
    val decision: String,
    val partyId: UUID?,
    val caseId: UUID?,
    val verifiedClaims: PidClaimsResponse,
    val loa: String,
)

/**
 * Map a verified-and-resolved EUDI result to the wire response. Shared by the direct M2M
 * `verify-presentation` endpoint and the OpenID4VP `presentation-requests/{id}` poll so the
 * decision/claims shape stays identical across both ingress paths (ADR-0094).
 */
fun EudiResolutionResult.toVerifyResponse(): VerifyPresentationResponse {
    val claimsResponse = claims.toResponse()
    return when (val r = resolution) {
        is ResolutionResult.MatchExisting ->
            VerifyPresentationResponse("MATCH_EXISTING", r.partyId, null, claimsResponse, claims.levelOfAssurance)
        is ResolutionResult.NoMatch ->
            VerifyPresentationResponse("NO_MATCH", null, null, claimsResponse, claims.levelOfAssurance)
        is ResolutionResult.NeedsManualVerification ->
            VerifyPresentationResponse(
                "NEEDS_MANUAL_VERIFICATION",
                null,
                r.caseId,
                claimsResponse,
                claims.levelOfAssurance,
            )
    }
}

/** Response for `POST /api/v1/parties/eudi/presentation-requests` — the OpenID4VP authorization request. */
data class CreatePresentationRequestResponse(
    /** Opaque transaction id; also the OpenID4VP `state`. Poll this to collect the result. */
    val transactionId: String,
    /** The `openid4vp://` authorization request URI to render as a QR / deep-link for the wallet. */
    val authorizationRequestUri: String,
    /** When the exchange (and its single-use nonce) expires. */
    val expiresAt: Instant,
)

/** Response for `GET /api/v1/parties/eudi/presentation-requests/{id}` — the exchange's current state. */
data class PresentationExchangeStatusResponse(
    val transactionId: String,
    /** PENDING | COMPLETED | EXPIRED. */
    val status: String,
    /** Set only when [status] is COMPLETED. */
    val decision: String?,
    val partyId: UUID?,
    val caseId: UUID?,
    val verifiedClaims: PidClaimsResponse?,
    val loa: String?,
)

fun PidClaims.toResponse(): PidClaimsResponse = PidClaimsResponse(
    subjectIdMasked = maskSubjectId(subjectId),
    givenName = givenName,
    familyName = familyName,
    birthDate = birthDate,
    birthPlace = birthPlace,
    nationalities = nationalities,
    issuingCountry = issuingCountry,
    issuer = issuer,
    levelOfAssurance = levelOfAssurance,
)

/** Mask the government PID subject identifier: keep the source prefix + last 4 chars only. */
private fun maskSubjectId(subjectId: String): String {
    val tail = subjectId.takeLast(MASK_TAIL)
    val prefix = subjectId.substringBefore(":", missingDelimiterValue = "")
    return if (prefix.isNotEmpty()) "$prefix:***$tail" else "***$tail"
}

private const val MASK_TAIL = 4
