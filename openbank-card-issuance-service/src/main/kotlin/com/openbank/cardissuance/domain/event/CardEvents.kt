// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.event

import com.openbank.cardissuance.domain.model.*
import java.time.Instant
import java.util.UUID

sealed class CardEvent {
    abstract val cardId: UUID

    /**
     * Business time of the change, serialized into the outbox payload and read downstream as the
     * event's business date.
     *
     * **Deliberately has no default.** It used to default to `Instant.EPOCH` on all four subtypes —
     * the same shape as `AuditEvent.timestamp` / `FlagExposure.timestamp` (#3874/#3883) and
     * `OutboxMessage.createdAt` (#3272/#3338), where the default WAS what every row got. Here it
     * never fired: all four call sites in `CardService` pass a real clock reading, and the 24 rows
     * parked in `card_outbox` all carry a real `occurredAt` in their payload. So this is a trap
     * that had not sprung, and the way to keep it that way is to make omitting it a compile error
     * rather than a silent 1970 (#4005).
     */
    abstract val occurredAt: Instant
}
data class CardIssued(
    override val cardId: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val cardType: CardType,
    val network: CardNetwork,
    val maskedPan: String,
    override val occurredAt: Instant,
) : CardEvent()
data class CardStatusChanged(
    override val cardId: UUID,
    val previousStatus: CardStatus,
    val newStatus: CardStatus,
    val reason: String?,
    val changedBy: String,
    override val occurredAt: Instant,
) : CardEvent()
data class CardLimitsChanged(
    override val cardId: UUID,
    val dailyLimitMinorUnits: Long,
    val monthlyLimitMinorUnits: Long,
    val changedBy: String,
    override val occurredAt: Instant,
) : CardEvent()
data class CardControlsChanged(
    override val cardId: UUID,
    val contactlessEnabled: Boolean,
    val onlineEnabled: Boolean,
    val atmEnabled: Boolean,
    val abroadEnabled: Boolean,
    val changedBy: String,
    override val occurredAt: Instant,
) : CardEvent()
