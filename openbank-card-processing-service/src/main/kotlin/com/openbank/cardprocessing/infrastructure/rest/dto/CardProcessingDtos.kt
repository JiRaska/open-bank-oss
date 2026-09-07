// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest.dto

import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import java.time.Instant
import java.util.UUID

/**
 * An authorisation as the acquirer presents it.
 *
 * `amountMinorUnits` is a `Long`, matching the scheme message. A decimal here would invite a
 * rounding decision at the edge that has no correct answer.
 */
data class AuthorizationRequestDto(
    val cardId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String? = null,
    val merchantName: String? = null,
    val merchantCountry: String? = null,
    val networkReference: String? = null,
    /**
     * The AP2 mandate an agent presented with this purchase (ADR-0283 D6). Absent for an ordinary
     * human purchase, which this path leaves unchanged.
     */
    val agentMandate: AgentMandateDto? = null,
)

/**
 * A presented AP2 mandate, as the acquirer forwards it.
 *
 * Carried opaquely: card-processing does not parse the JOSE encoding, does not check the signature
 * and holds no trust list. It forwards these fields to ap2-service (ADR-0193) and acts on the
 * verdict — a second verifier here would be a second opinion about whether an agent may spend.
 */
data class AgentMandateDto(
    val kind: String,
    val issuer: String,
    val subject: String,
    val signingInput: String,
    val signatureB64: String,
    val algorithm: String,
    val payee: String,
    val amountCapMinorUnits: Long,
    val currency: String,
    val expiresAt: Instant,
    val singleUse: Boolean = false,
    /** The acting agent, forwarded so ap2-service authorises the call as the agent, not as us. */
    val agentId: String? = null,
)

data class PresentmentRequestDto(val amountMinorUnits: Long, val currencyCode: String)

data class AuthorizationResponseDto(
    val id: UUID,
    val cardId: UUID,
    val accountId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val status: String,
    val category: String,
    val declineReason: String?,
    val clearedAmountMinorUnits: Long,
    val heldAmountMinorUnits: Long,
    val merchantName: String?,
    val merchantCountry: String?,
    val networkReference: String?,
    /** Null for a human purchase; the acting agent for one made under an AP2 mandate. */
    val initiatedByAgentId: String?,
    val authorizedAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun of(a: CardAuthorization) = AuthorizationResponseDto(
            id = a.id,
            cardId = a.cardId,
            accountId = a.accountId,
            amountMinorUnits = a.amountMinorUnits,
            currencyCode = a.currencyCode,
            channel = a.channel,
            status = a.status.name,
            category = a.category,
            declineReason = a.declineReason,
            clearedAmountMinorUnits = a.clearedAmountMinorUnits,
            // Derived, so a client never has to subtract two numbers and get a different answer
            // than the service would.
            heldAmountMinorUnits = a.heldAmountMinorUnits,
            merchantName = a.merchantName,
            merchantCountry = a.merchantCountry,
            networkReference = a.networkReference,
            initiatedByAgentId = a.initiatedByAgentId,
            authorizedAt = a.authorizedAt,
            expiresAt = a.expiresAt,
        )
    }
}

data class AuthorizationListResponse(val data: List<AuthorizationResponseDto>, val count: Int)

data class RefusalResponse(val reason: String, val message: String)
