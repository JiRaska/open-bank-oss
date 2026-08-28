// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application

import com.openbank.referral.application.port.out.ReferralAuditRepository
import com.openbank.referral.application.port.out.ReferralInviteRepository
import com.openbank.referral.application.port.out.ReferralProgramRepository
import com.openbank.referral.application.port.out.ReferralRewardRepository
import com.openbank.referral.domain.ProgramStatus
import com.openbank.referral.domain.ReferralProgram
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ReferralServiceTest {
    private val programs = mockk<ReferralProgramRepository>()
    private val service = ReferralService(
        programs = programs,
        invites = mockk<ReferralInviteRepository>(),
        rewards = mockk<ReferralRewardRepository>(),
        audit = mockk<ReferralAuditRepository>(),
        clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `published program lookup hides draft and returns only published immutable reference`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { programs.find(id) } returns program(id, ProgramStatus.DRAFT)
        assertThat(service.publishedProgram(id)).isNull()

        val published = program(id, ProgramStatus.PUBLISHED)
        coEvery { programs.find(id) } returns published
        assertThat(service.publishedProgram(id)).isEqualTo(published)

        coEvery { programs.find(id) } returns program(
            id = id,
            status = ProgramStatus.PUBLISHED,
            attributionWindowEndsAt = Instant.parse("2026-08-27T23:59:59Z"),
        )
        assertThat(service.publishedProgram(id)).isNull()
    }

    @Test
    fun `published programme catalogue fails closed for drafts and expired programmes`(): Unit = runBlocking {
        val published = program(UUID.randomUUID(), ProgramStatus.PUBLISHED, name = "alpha", version = 2)
        val draft = program(UUID.randomUUID(), ProgramStatus.DRAFT, name = "beta", version = 1)
        val expired = program(
            UUID.randomUUID(),
            ProgramStatus.PUBLISHED,
            attributionWindowEndsAt = Instant.parse("2026-08-27T23:59:59Z"),
            name = "gamma",
            version = 1,
        )
        coEvery { programs.listPublished(any()) } returns listOf(published, draft, expired)

        assertThat(service.publishedPrograms()).containsExactly(published)
    }

    private fun program(
        id: UUID,
        status: ProgramStatus,
        attributionWindowEndsAt: Instant = Instant.parse("2026-12-31T00:00:00Z"),
        name: String = "term-deposit-referral",
        version: Int = 2,
    ) = ReferralProgram(
        id = id,
        name = name,
        version = version,
        rewardAmount = BigDecimal.TEN,
        currency = "EUR",
        qualifyingEvent = "account.opened",
        attributionWindowEndsAt = attributionWindowEndsAt,
        status = status,
        maker = "maker",
        checker = if (status == ProgramStatus.PUBLISHED) "checker" else null,
        createdAt = Instant.parse("2026-08-28T00:00:00Z"),
        publishedAt = if (status == ProgramStatus.PUBLISHED) Instant.parse("2026-08-28T00:01:00Z") else null,
    )
}
