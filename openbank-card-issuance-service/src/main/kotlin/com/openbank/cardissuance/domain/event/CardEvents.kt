// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.event

import com.openbank.cardissuance.domain.model.*
import java.time.Instant
import java.util.UUID

sealed class CardEvent {
    abstract val cardId: UUID
    abstract val occurredAt: Instant
}
data class CardIssued(
    override val cardId: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val cardType: CardType,
    val network: CardNetwork,
    val maskedPan: String,
    override val occurredAt: Instant = Instant.EPOCH,
) : CardEvent()
data class CardStatusChanged(
    override val cardId: UUID,
    val previousStatus: CardStatus,
    val newStatus: CardStatus,
    val reason: String?,
    val changedBy: String,
    override val occurredAt: Instant = Instant.EPOCH,
) : CardEvent()
data class CardLimitsChanged(
    override val cardId: UUID,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val changedBy: String,
    override val occurredAt: Instant = Instant.EPOCH,
) : CardEvent()
