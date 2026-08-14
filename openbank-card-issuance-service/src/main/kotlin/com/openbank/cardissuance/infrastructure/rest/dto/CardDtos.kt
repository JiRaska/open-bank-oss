// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest.dto

import com.openbank.cardissuance.application.port.`in`.*
import com.openbank.cardissuance.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IssueCardRequest(
    val partyId: UUID,
    val accountId: UUID,
    val productCode: String,
    val cardType: CardType,
    val network: CardNetwork,
    val cardholderName: String,
    val embossedName: String,
    val currency: String,
    val dailyLimitMinorUnits: Long = 500_000L,
    val monthlyLimitMinorUnits: Long = 5_000_000L,
    val deliveryAddress: String? = null,
    /** ADR-0249 D1 — the grant authorising a card for a holder who is not the account owner. */
    val delegationGrantId: UUID? = null,
) {
    fun toCommand(idempotencyKey: String) = IssueCardCommand(
        idempotencyKey, partyId, accountId, productCode, cardType, network,
        cardholderName, embossedName, currency, dailyLimitMinorUnits, monthlyLimitMinorUnits, deliveryAddress,
        delegationGrantId,
    )
}

data class CardStatusRequest(val reason: String? = null)

data class CardResponse(
    val id: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val productCode: String,
    val cardType: CardType,
    val network: CardNetwork,
    val maskedPan: String,
    val cardholderName: String,
    val embossedName: String,
    val expiryDate: LocalDate,
    val status: CardStatus,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val currency: String,
    val deliveryAddress: String?,
    val activatedAt: Instant?,
    val blockedAt: Instant?,
    val blockedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val contactlessEnabled: Boolean = true,
    val onlineEnabled: Boolean = true,
    val atmEnabled: Boolean = true,
    val abroadEnabled: Boolean = true,
    /**
     * ADR-0249 D1. Present only on an additional-cardholder card. Callers should branch on
     * [delegated] rather than on this being non-null; the id is exposed so an operator can trace
     * the card back to the authority it rests on.
     */
    val delegationGrantId: UUID? = null,
    /** True when this card was issued to a delegate rather than the account owner (ADR-0249 D1). */
    val delegated: Boolean = false,
    /** SINGLE_USE only: when the card stops being usable even if never presented. */
    val expiresAt: Instant? = null,
    /** Why the card reached a terminal status; null while it is alive. */
    val closedReason: CardClosedReason? = null,
)

fun Card.toResponse() = CardResponse(
    id, partyId, accountId, productCode, cardType, network, maskedPan,
    cardholderName, embossedName, expiryDate, status,
    dailyLimitMinorUnits, monthlyLimitMinorUnits, currency, deliveryAddress,
    activatedAt, blockedAt, blockedReason, createdAt, updatedAt,
    contactlessEnabled, onlineEnabled, atmEnabled, abroadEnabled,
    delegationGrantId, isDelegated,
    expiresAt, closedReason,
)

/**
 * A virtual card's decrypted synthetic credential. Serialised once, straight to the caller, under
 * `Cache-Control: no-store` — it is never logged, cached or persisted in this shape.
 */
data class CardSecureDetailsResponse(
    val pan: String,
    val cvv: String,
    val expiryDate: LocalDate,
    val cardholderName: String,
    val network: CardNetwork,
)

fun CardSecureDetails.toResponse() = CardSecureDetailsResponse(pan, cvv, expiryDate, cardholderName, network)

/**
 * What a party may still do on a product. `source` = `FALLBACK` means product-catalog did not
 * answer: `maxCards`/`remaining` are then `-1` ("no known cap"), not `0`.
 */
data class CardEntitlementsResponse(
    val productCode: String,
    val maxCards: Int,
    val issued: Int,
    val remaining: Int,
    val virtualCardAllowed: Boolean,
    val singleUseAllowed: Boolean,
    val networks: List<CardNetwork>,
    val tiers: List<String>,
    val monthlyFeePerCard: Double,
    val enabled: Boolean,
    val source: EntitlementSource,
)

fun CardEntitlements.toResponse() = CardEntitlementsResponse(
    productCode, maxCards, issued, remaining, virtualCardAllowed, singleUseAllowed,
    networks, tiers, monthlyFeePerCard, enabled, source,
)

/** Customer/operator request to set a card's spending limits (minor units). */
data class UpdateLimitsRequest(val dailyLimitMinorUnits: Long, val monthlyLimitMinorUnits: Long)

/** Customer/operator request to set a card's channel controls (which rails may transact). */
data class UpdateControlsRequest(
    val contactlessEnabled: Boolean,
    val onlineEnabled: Boolean,
    val atmEnabled: Boolean,
    val abroadEnabled: Boolean,
)
