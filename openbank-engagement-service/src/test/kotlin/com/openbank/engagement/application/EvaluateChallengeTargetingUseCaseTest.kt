// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application

import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.application.usecase.EvaluateChallengeTargetingUseCase
import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EvaluateChallengeTargetingUseCaseTest {

    private val party = UUID.randomUUID()
    private val challengeId = "COMPLETE_BUDGETING_COURSE"

    private fun gate(consented: Boolean = true): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { _, _ -> consented },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant) = 0
            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
        },
        suppression = ContactSuppressionPort { emptyList() },
        policy = ContactPolicy(),
    )

    private fun membershipRepo(state: RewardsHubMembership?): RewardsHubMembershipRepository {
        val repo = mockk<RewardsHubMembershipRepository>()
        coEvery { repo.current(any()) } returns state
        return repo
    }

    private fun adverseStateRepo(states: Set<AdverseState> = emptySet()): AdverseStateRepository {
        val repo = mockk<AdverseStateRepository>()
        coEvery { repo.activeStates(any()) } returns states
        return repo
    }

    @Test
    fun `an opted-in, consented, eligible party is targetable`(): Unit = runBlocking {
        val result = EvaluateChallengeTargetingUseCase(
            gate(consented = true),
            membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now())),
            adverseStateRepo(),
        ).evaluate(party, challengeId)

        assertThat(result).isEqualTo(EvaluateChallengeTargetingUseCase.Result.Eligible)
    }

    @Test
    fun `an unknown challenge id is never targetable`(): Unit = runBlocking {
        val result = EvaluateChallengeTargetingUseCase(
            gate(),
            membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now())),
            adverseStateRepo(),
        ).evaluate(party, "NOT_A_CHALLENGE")

        assertThat(result).isInstanceOf(EvaluateChallengeTargetingUseCase.Result.NotEligible::class.java)
    }

    @Test
    fun `a party who never opted in is not targetable`(): Unit = runBlocking {
        val result = EvaluateChallengeTargetingUseCase(gate(), membershipRepo(null), adverseStateRepo())
            .evaluate(party, challengeId)

        assertThat(result).isInstanceOf(EvaluateChallengeTargetingUseCase.Result.NotEligible::class.java)
    }

    @Test
    fun `a vulnerable customer is excluded from targeting even when opted in and consented`(): Unit = runBlocking {
        val result = EvaluateChallengeTargetingUseCase(
            gate(consented = true),
            membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now())),
            adverseStateRepo(setOf(AdverseState.FRAUD_HOLD)),
        ).evaluate(party, challengeId)

        assertThat(result).isInstanceOf(EvaluateChallengeTargetingUseCase.Result.NotEligible::class.java)
        assertThat((result as EvaluateChallengeTargetingUseCase.Result.NotEligible).reason)
            .contains("vulnerable-customer")
    }

    @Test
    fun `a party without marketing consent is not targetable even when opted in`(): Unit = runBlocking {
        val result = EvaluateChallengeTargetingUseCase(
            gate(consented = false),
            membershipRepo(RewardsHubMembership.OptedIn(party, Instant.now())),
            adverseStateRepo(),
        ).evaluate(party, challengeId)

        assertThat(result).isInstanceOf(EvaluateChallengeTargetingUseCase.Result.NotEligible::class.java)
    }
}
