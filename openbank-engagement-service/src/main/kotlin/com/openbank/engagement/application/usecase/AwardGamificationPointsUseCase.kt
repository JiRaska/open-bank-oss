// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.GamificationAwardRepository
import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.engagement.domain.model.gamification.ChallengeCatalog
import com.openbank.engagement.domain.model.gamification.GamificationAwardRule
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * ADR-0220 D3's earn evaluation, wired to this service's OWN already-real signal — a `CONVERSION`
 * [EngagementEvent] on [SurfaceSlot.REWARDS_HUB] whose `contentId` names a [ChallengeCatalog]
 * entry — rather than a new Kafka topic invented for this slice. The ADR's full D3 intent (savings
 * deposits, logins, on-time repayments) needs producers this codebase does not have yet; awarding
 * from a signal this service can actually observe end-to-end today is the same "honest dependency
 * statement" discipline ADR-0220 D5 already applies to pre-approved offers, not a scope narrowing
 * invented for this PR.
 *
 * This is deliberately NOT a `@MarketingCallSite`: awarding points for something the party already
 * did is a reward record, not a marketing touch reaching the delivery/surface layer. The marketing
 * touch — deciding whether to INVITE a party into a new challenge — is
 * [EvaluateChallengeTargetingUseCase], which does carry the annotation.
 *
 * Deliberately does NOT read [com.openbank.engagement.domain.model.AdverseState] here. ADR-0220
 * D3 rule 5 excludes vulnerable customers from *targeting* — "excluded from challenge targeting
 * and promotional surfaces at the eligibility stage, while remaining free to use the hub on their
 * own initiative" — and this use case only ever fires after a party has already completed a
 * challenge on their own initiative, which the ADR names as the case that must keep working. The
 * eligibility check belongs solely in [EvaluateChallengeTargetingUseCase], which decides whether
 * to invite; conflating the two would silently withhold an already-earned reward from a party the
 * ADR explicitly says must keep receiving it.
 */
@ApplicationScoped
class AwardGamificationPointsUseCase(
    private val membership: RewardsHubMembershipRepository,
    private val awards: GamificationAwardRepository,
) {
    suspend fun evaluate(event: EngagementEvent, correlationEventId: UUID) {
        if (event.type != EngagementEventType.CONVERSION || event.slot != SurfaceSlot.REWARDS_HUB) return
        val challenge = ChallengeCatalog.ALL[event.contentId] ?: return

        // D3 rule 2: the rewards hub visibility toggle. A party who never opted in — or who opted
        // out — earns nothing for an organic completion; `current() == null` (never opted in
        // either way) is treated the same as an explicit OptedOut, since the feature default is off.
        if (membership.current(event.partyId) !is RewardsHubMembership.OptedIn) return

        if (awards.alreadyAwarded(event.partyId, challenge.id)) return

        val award = GamificationAwardRule.award(
            challenge = challenge,
            partyId = event.partyId,
            correlationEventId = correlationEventId,
            occurredAt = event.occurredAt,
        )
        awards.save(award)
    }
}
