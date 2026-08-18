// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application

import com.openbank.engagement.application.port.out.GamificationAwardRepository
import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.application.usecase.AwardGamificationPointsUseCase
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.engagement.domain.model.gamification.GamificationAward
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AwardGamificationPointsUseCaseTest {

    private val party = UUID.randomUUID()

    private fun event(
        type: EngagementEventType = EngagementEventType.CONVERSION,
        slot: SurfaceSlot = SurfaceSlot.REWARDS_HUB,
        contentId: String = "COMPLETE_BUDGETING_COURSE",
    ) = EngagementEvent(party, contentId, slot, type, Instant.now())

    private fun membershipRepo(state: RewardsHubMembership?): RewardsHubMembershipRepository {
        val repo = mockk<RewardsHubMembershipRepository>()
        coEvery { repo.current(any()) } returns state
        return repo
    }

    private fun awardsRepo(alreadyAwarded: Boolean = false): GamificationAwardRepository {
        val repo = mockk<GamificationAwardRepository>()
        coEvery { repo.alreadyAwarded(any(), any()) } returns alreadyAwarded
        coEvery { repo.save(any()) } returns Unit
        return repo
    }

    @Test
    fun `an opted-in party completing a catalogued challenge earns an award`(): Unit = runBlocking {
        val membership = membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now()))
        val awards = awardsRepo()
        val correlationId = UUID.randomUUID()

        AwardGamificationPointsUseCase(membership, awards).evaluate(event(), correlationId)

        coVerify(exactly = 1) {
            awards.save(
                withArg<GamificationAward> {
                    org.assertj.core.api.Assertions.assertThat(it.partyId).isEqualTo(party)
                    org.assertj.core.api.Assertions.assertThat(it.correlationEventId).isEqualTo(correlationId)
                },
            )
        }
    }

    @Test
    fun `a party who never opted in earns nothing`(): Unit = runBlocking {
        val membership = membershipRepo(null)
        val awards = awardsRepo()

        AwardGamificationPointsUseCase(membership, awards).evaluate(event(), UUID.randomUUID())

        coVerify(exactly = 0) { awards.save(any()) }
    }

    @Test
    fun `an opted-out party earns nothing even for a genuine completion`(): Unit = runBlocking {
        val membership = membershipRepo(RewardsHubMembership.OptedOut(party, Instant.now()))
        val awards = awardsRepo()

        AwardGamificationPointsUseCase(membership, awards).evaluate(event(), UUID.randomUUID())

        coVerify(exactly = 0) { awards.save(any()) }
    }

    @Test
    fun `an event on an uncatalogued content id triggers no award`(): Unit = runBlocking {
        val membership = membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now()))
        val awards = awardsRepo()

        AwardGamificationPointsUseCase(membership, awards).evaluate(
            event(contentId = "NOT_A_CHALLENGE"),
            UUID.randomUUID(),
        )

        coVerify(exactly = 0) { awards.save(any()) }
    }

    @Test
    fun `a non-CONVERSION event on the same content triggers no award`(): Unit = runBlocking {
        val membership = membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now()))
        val awards = awardsRepo()

        AwardGamificationPointsUseCase(membership, awards).evaluate(
            event(type = EngagementEventType.CLICK),
            UUID.randomUUID(),
        )

        coVerify(exactly = 0) { awards.save(any()) }
    }

    @Test
    fun `an already-awarded correlation id is not awarded twice`(): Unit = runBlocking {
        val membership = membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now()))
        val awards = awardsRepo(alreadyAwarded = true)

        AwardGamificationPointsUseCase(membership, awards).evaluate(event(), UUID.randomUUID())

        coVerify(exactly = 0) { awards.save(any()) }
    }
}
