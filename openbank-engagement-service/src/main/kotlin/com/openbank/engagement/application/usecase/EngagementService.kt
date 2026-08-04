// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.EngagementRepository
import com.openbank.engagement.domain.event.BadgeUnlocked
import com.openbank.engagement.domain.event.StreakUpdated
import com.openbank.engagement.domain.event.XpEarned
import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EarnSource
import com.openbank.engagement.domain.model.EngagementProfile
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class EngagementService(
    private val repository: EngagementRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.engagement.yearly-reward-cap", defaultValue = "5000") private val yearlyRewardCap:
    Int,
) {

    suspend fun getOrCreate(partyId: UUID): EngagementProfile = repository.findByParty(partyId)
        ?: EngagementProfile(
            partyId = partyId,
            enrolled = false,
            adverseState = false,
            streakDays = 0,
            lastActivityAt = null,
            totalPoints = 0,
            earnedThisYear = 0,
            badges = emptySet(),
            createdAt = clock.instant(),
            updatedAt = clock.instant(),
        ).also { repository.save(it) }

    suspend fun optIn(partyId: UUID): EngagementProfile {
        val profile = getOrCreate(partyId)
        if (profile.enrolled) return profile
        return repository.save(profile.optIn(clock.instant()))
    }

    suspend fun optOut(partyId: UUID): EngagementProfile {
        val profile = getOrCreate(partyId)
        if (!profile.enrolled) return profile
        return repository.save(profile.optOut(clock.instant()))
    }

    suspend fun recordActivity(partyId: UUID): EngagementProfile {
        val now = clock.instant()
        val profile = getOrCreate(partyId)
        if (!profile.enrolled) return profile
        val updated = profile.recordActivity(now)
        val event =
            StreakUpdated(
                aggregateId = Ids.newId(),
                partyId = partyId,
                streakDays = updated.streakDays,
                occurredAt = now,
            )
        return repository.save(updated, event)
    }

    suspend fun award(partyId: UUID, points: Int, source: EarnSource): EngagementProfile {
        val now = clock.instant()
        val profile = getOrCreate(partyId)
        val updated = profile.award(points, yearlyRewardCap, now)
        val event =
            XpEarned(
                aggregateId = Ids.newId(),
                partyId = partyId,
                points = points,
                source = source,
                totalPoints = updated.totalPoints,
                occurredAt = now,
            )
        return repository.save(updated, event)
    }

    suspend fun unlock(partyId: UUID, badge: BadgeType): EngagementProfile {
        val now = clock.instant()
        val profile = getOrCreate(partyId)
        val before = profile.badges
        val updated = profile.unlock(badge, now)
        if (badge in before) return updated
        val event = BadgeUnlocked(aggregateId = Ids.newId(), partyId = partyId, badge = badge, occurredAt = now)
        return repository.save(updated, event)
    }

    suspend fun setAdverseState(partyId: UUID, active: Boolean): EngagementProfile {
        val profile = getOrCreate(partyId)
        return repository.save(profile.copy(adverseState = active, updatedAt = Instant.now(clock)))
    }
}
