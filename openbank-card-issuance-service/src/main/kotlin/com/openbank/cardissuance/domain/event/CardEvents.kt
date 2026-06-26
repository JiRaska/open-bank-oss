// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.cardissuance.domain.event

import com.openbank.cardissuance.domain.model.*
import java.time.Instant; import java.util.UUID

sealed class CardEvent { abstract val cardId: UUID; abstract val occurredAt: Instant }
data class CardIssued(override val cardId: UUID, val partyId: UUID, val accountId: UUID,
    val cardType: CardType, val network: CardNetwork, val maskedPan: String,
    override val occurredAt: Instant = Instant.EPOCH) : CardEvent()
data class CardStatusChanged(override val cardId: UUID, val previousStatus: CardStatus,
    val newStatus: CardStatus, val reason: String?, val changedBy: String,
    override val occurredAt: Instant = Instant.EPOCH) : CardEvent()
