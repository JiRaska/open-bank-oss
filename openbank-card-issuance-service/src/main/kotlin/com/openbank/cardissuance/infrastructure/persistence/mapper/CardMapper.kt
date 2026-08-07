// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.mapper

import com.openbank.cardissuance.domain.model.*
import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity

fun CardEntity.toDomain() = Card(
    id = id, idempotencyKey = idempotencyKey, partyId = partyId, accountId = accountId,
    productCode = productCode, cardType = CardType.valueOf(cardType), network = CardNetwork.valueOf(network),
    maskedPan = maskedPan, cardholderName = cardholderName, embossedName = embossedName,
    expiryDate = expiryDate, status = CardStatus.valueOf(status),
    dailyLimitMinorUnits = dailyLimitMinorUnits, monthlyLimitMinorUnits = monthlyLimitMinorUnits,
    currency = currency, deliveryAddress = deliveryAddress,
    activatedAt = activatedAt, blockedAt = blockedAt, blockedReason = blockedReason,
    expiresAt = expiresAt,
    closedReason = closedReason?.let { r -> runCatching { CardClosedReason.valueOf(r) }.getOrNull() },
    createdAt = createdAt, updatedAt = updatedAt,
    contactlessEnabled = contactlessEnabled, onlineEnabled = onlineEnabled,
    atmEnabled = atmEnabled, abroadEnabled = abroadEnabled,
    panEncrypted = panEncrypted, cvvEncrypted = cvvEncrypted,
)

fun Card.toEntity() = CardEntity().also { e ->
    e.id = id
    e.idempotencyKey = idempotencyKey
    e.partyId = partyId
    e.accountId = accountId
    e.productCode = productCode
    e.cardType = cardType.name
    e.network = network.name
    e.maskedPan = maskedPan
    e.cardholderName = cardholderName
    e.embossedName = embossedName
    e.expiryDate = expiryDate
    e.status = status.name
    e.dailyLimitMinorUnits = dailyLimitMinorUnits
    e.monthlyLimitMinorUnits = monthlyLimitMinorUnits
    e.contactlessEnabled = contactlessEnabled
    e.onlineEnabled = onlineEnabled
    e.atmEnabled = atmEnabled
    e.abroadEnabled = abroadEnabled
    e.currency = currency
    e.deliveryAddress = deliveryAddress
    e.activatedAt = activatedAt
    e.blockedAt = blockedAt
    e.blockedReason = blockedReason
    e.expiresAt = expiresAt
    e.closedReason = closedReason?.name
    e.createdAt = createdAt
    e.updatedAt = updatedAt
    e.panEncrypted = panEncrypted
    e.cvvEncrypted = cvvEncrypted
}
