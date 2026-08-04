// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.application.port.out.EngagementRepository
import com.openbank.engagement.application.usecase.EngagementService
import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EarnSource
import com.openbank.engagement.domain.model.EngagementProfile
import com.openbank.libs.domain.event.DomainEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class EngagementServiceTest {

    private val now = Instant.parse("2026-08-03T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val partyId = UUID.randomUUID()

    private val store = mutableMapOf<UUID, EngagementProfile>()

    private val repo = object : EngagementRepository {
        override suspend fun findByParty(id: UUID) = store[id]
        override suspend fun save(p: EngagementProfile) = p.also { store[p.partyId] = p }
        override suspend fun save(p: EngagementProfile, e: DomainEvent) = save(p)
    }

    private val service = EngagementService(repo, clock, yearlyRewardCap = 500)

    @Test
    fun `getOrCreate makes a not-enrolled zero-state profile`(): Unit = runBlocking {
        val p = service.getOrCreate(partyId)
        assertThat(p.enrolled).isFalse()
        assertThat(p.totalPoints).isZero()
    }

    @Test
    fun `optIn flips enrolled, optOut keeps earned points`(): Unit = runBlocking {
        service.optIn(partyId)
        service.award(partyId, 100, EarnSource.SAVINGS_DEPOSIT)
        service.optOut(partyId)
        val p = service.getOrCreate(partyId)
        assertThat(p.enrolled).isFalse()
        assertThat(p.totalPoints).isEqualTo(100)
    }

    @Test
    fun `award increments totalPoints`(): Unit = runBlocking {
        service.optIn(partyId)
        service.award(partyId, 50, EarnSource.DAILY_ACTIVITY)
        assertThat(service.getOrCreate(partyId).totalPoints).isEqualTo(50)
    }

    @Test
    fun `award denied when not opted in (invariant 2)`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.award(partyId, 10, EarnSource.DAILY_ACTIVITY) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unlock earns a badge, second unlock is idempotent`(): Unit = runBlocking {
        service.optIn(partyId)
        service.unlock(partyId, BadgeType.FIRST_SAVINGS_GOAL)
        service.unlock(partyId, BadgeType.FIRST_SAVINGS_GOAL)
        assertThat(service.getOrCreate(partyId).badges).containsExactly(BadgeType.FIRST_SAVINGS_GOAL)
    }
}
