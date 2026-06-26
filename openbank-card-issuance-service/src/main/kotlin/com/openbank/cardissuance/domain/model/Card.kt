// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.cardissuance.domain.model

import java.time.Instant; import java.time.LocalDate; import java.util.UUID

enum class CardStatus { PENDING, ACTIVE, SUSPENDED, BLOCKED, EXPIRED, CANCELLED }
enum class CardType   { DEBIT, CREDIT, PREPAID, VIRTUAL }
enum class CardNetwork{ VISA, MASTERCARD, AMEX, UNIONPAY }

data class Card(
    val id: UUID, val idempotencyKey: String,
    val partyId: UUID, val accountId: UUID,
    val productCode: String, val cardType: CardType, val network: CardNetwork,
    val maskedPan: String, val cardholderName: String, val embossedName: String,
    val expiryDate: LocalDate, val status: CardStatus,
    val dailyLimitMinorUnits: Long, val monthlyLimitMinorUnits: Long, val currency: String,
    val deliveryAddress: String?,
    val activatedAt: Instant?, val blockedAt: Instant?, val blockedReason: String?,
    val createdAt: Instant, val updatedAt: Instant
) {
    fun activate(now: Instant = Instant.EPOCH) = also {
        require(status == CardStatus.PENDING) { "Only PENDING cards can be activated, current: $status" }
    }.copy(status = CardStatus.ACTIVE, activatedAt = now, updatedAt = now)

    fun block(reason: String, now: Instant = Instant.EPOCH) = also {
        require(status in setOf(CardStatus.ACTIVE, CardStatus.SUSPENDED)) { "Cannot block card in status $status" }
        require(reason.isNotBlank()) { "Block reason required" }
    }.copy(status = CardStatus.BLOCKED, blockedAt = now, blockedReason = reason, updatedAt = now)

    fun suspend(now: Instant = Instant.EPOCH) = also {
        require(status == CardStatus.ACTIVE) { "Only ACTIVE cards can be suspended" }
    }.copy(status = CardStatus.SUSPENDED, updatedAt = now)

    fun resume(now: Instant = Instant.EPOCH) = also {
        require(status == CardStatus.SUSPENDED) { "Only SUSPENDED cards can be resumed" }
    }.copy(status = CardStatus.ACTIVE, updatedAt = now)
}
