// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application

import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.application.usecase.SetRewardsHubMembershipUseCase
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SetRewardsHubMembershipUseCaseTest {

    private val party = UUID.randomUUID()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `optIn saves an OptedIn membership stamped with the injected clock`(): Unit = runBlocking {
        val repo = mockk<RewardsHubMembershipRepository>()
        coEvery { repo.save(any()) } returns Unit

        SetRewardsHubMembershipUseCase(repo, fixedClock).optIn(party)

        coVerify { repo.save(RewardsHubMembership.OptedIn(party, Instant.now(fixedClock))) }
    }

    @Test
    fun `optOut saves an OptedOut membership stamped with the injected clock`(): Unit = runBlocking {
        val repo = mockk<RewardsHubMembershipRepository>()
        coEvery { repo.save(any()) } returns Unit

        SetRewardsHubMembershipUseCase(repo, fixedClock).optOut(party)

        coVerify { repo.save(RewardsHubMembership.OptedOut(party, Instant.now(fixedClock))) }
    }
}
