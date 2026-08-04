// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.event

import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EarnSource
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class XpEarned(
    override val aggregateId: UUID,
    val partyId: UUID,
    val points: Int,
    val source: EarnSource,
    val totalPoints: Int,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Engagement"
    override val eventType = "XpEarned"
    override val version = 1L
}

data class BadgeUnlocked(
    override val aggregateId: UUID,
    val partyId: UUID,
    val badge: BadgeType,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Engagement"
    override val eventType = "BadgeUnlocked"
    override val version = 1L
}

data class StreakUpdated(
    override val aggregateId: UUID,
    val partyId: UUID,
    val streakDays: Int,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Engagement"
    override val eventType = "StreakUpdated"
    override val version = 1L
}
