// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.domain.model.gamification.RewardsHubMembershipTransitions
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * ADR-0220 D3 rule 2's "leaving is one tap" as an explicit domain operation — the ONLY entry point
 * that may change a party's `RewardsHubMembership`, always via
 * [RewardsHubMembershipTransitions] (never a direct field assignment). No REST surface is added in
 * this slice (see the ADR/PR for why) — this use case is wired and unit-tested so the operation
 * itself is real ahead of the customer-facing endpoint, the same "infrastructure before the
 * customer surface" ordering `EligibilitySnapshot` materialisation already used.
 */
@ApplicationScoped
class SetRewardsHubMembershipUseCase(
    private val membership: RewardsHubMembershipRepository,
    private val clock: Clock,
) {
    suspend fun optIn(partyId: UUID) {
        membership.save(RewardsHubMembershipTransitions.optIn(partyId, Instant.now(clock)))
    }

    suspend fun optOut(partyId: UUID) {
        membership.save(RewardsHubMembershipTransitions.optOut(partyId, Instant.now(clock)))
    }
}
