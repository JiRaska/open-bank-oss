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
) {
    fun toCommand(idempotencyKey: String) = IssueCardCommand(
        idempotencyKey, partyId, accountId, productCode, cardType, network,
        cardholderName, embossedName, currency, dailyLimitMinorUnits, monthlyLimitMinorUnits, deliveryAddress,
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
)

fun Card.toResponse() = CardResponse(
    id, partyId, accountId, productCode, cardType, network, maskedPan,
    cardholderName, embossedName, expiryDate, status,
    dailyLimitMinorUnits, monthlyLimitMinorUnits, currency, deliveryAddress,
    activatedAt, blockedAt, blockedReason, createdAt, updatedAt,
)

/** Customer/operator request to set a card's spending limits (minor units). */
data class UpdateLimitsRequest(val dailyLimitMinorUnits: Long, val monthlyLimitMinorUnits: Long)
