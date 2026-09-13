// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.infrastructure.rest.dto

import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
import com.openbank.cardprocessing.domain.model.TokenRegistrations
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProvisionTokenRequestDto(val cardId: UUID, val requestorId: String, val requestorLabel: String)

data class ChangeTokenStatusRequestDto(val status: String)

data class TokenResponseDto(
    val id: UUID,
    val cardId: UUID,
    val tokenReference: String,
    val requestorId: String,
    val requestorLabel: String,
    val last4: String,
    val status: String,
    val scheme: String,
    val expiry: LocalDate?,
    val provisionedAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(r: CardTokenRegistration) = TokenResponseDto(
            id = r.id,
            cardId = r.cardId,
            tokenReference = r.tokenReference,
            requestorId = r.requestorId,
            requestorLabel = r.requestorLabel,
            last4 = r.last4,
            status = r.status.name,
            scheme = r.scheme.name,
            expiry = r.expiry,
            provisionedAt = r.provisionedAt,
            updatedAt = r.updatedAt,
        )
    }
}

/**
 * A token list that always states its provenance.
 *
 * [source] is `NETWORK` or `LOCAL_MIRROR` and [degradedReason] says why the network could not
 * answer. A client that renders the list without reading [source] shows possibly-stale token states
 * as current — which is why the field is required rather than optional, and why there is no variant
 * of this response without it.
 */
data class TokenListResponse(
    val tokens: List<TokenResponseDto>,
    val source: String,
    val degradedReason: String?,
    val count: Int,
) {
    companion object {
        fun of(registrations: TokenRegistrations) = TokenListResponse(
            tokens = registrations.tokens.map(TokenResponseDto::of),
            source = registrations.source.name,
            degradedReason = registrations.degradedReason,
            count = registrations.tokens.size,
        )
    }
}

data class OpenDisputeRequestDto(
    val authorizationId: UUID,
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
)

data class SubmitEvidenceRequestDto(val documentReference: String, val note: String?)

data class DisputeResponseDto(
    val id: UUID,
    val authorizationId: UUID,
    val cardId: UUID,
    val networkCaseId: String,
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val status: String,
    val scheme: String,
    /** The network's own status, verbatim. Present alongside [status], never instead of it. */
    val schemeStatus: String,
    val respondByDate: LocalDate?,
    val evidenceReference: String?,
    val openedAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(c: CardDisputeCase) = DisputeResponseDto(
            id = c.id,
            authorizationId = c.authorizationId,
            cardId = c.cardId,
            networkCaseId = c.networkCaseId,
            reasonCode = c.reasonCode,
            amountMinorUnits = c.amountMinorUnits,
            currencyCode = c.currencyCode,
            status = c.status.name,
            scheme = c.scheme.name,
            schemeStatus = c.schemeStatus,
            respondByDate = c.respondByDate,
            evidenceReference = c.evidenceReference,
            openedAt = c.openedAt,
            updatedAt = c.updatedAt,
        )
    }
}

data class DisputeListResponse(val disputes: List<DisputeResponseDto>, val count: Int)
