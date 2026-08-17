// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.port.out

import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.engagement.domain.model.gamification.GamificationAward
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import java.time.Instant
import java.util.UUID

interface EngagementEventRepository {
    /** Returns the persisted row's own generated id — the durable correlation id
     *  `AwardGamificationPointsUseCase` attaches to any [GamificationAward] this event triggers. */
    suspend fun save(event: EngagementEvent): UUID

    /** Ordered oldest-first — the shape [com.openbank.engagement.domain.model.DismissalRule] needs. */
    suspend fun recentForPartyAndSlot(partyId: UUID, slot: SurfaceSlot, since: Instant): List<EngagementEvent>

    suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int
}

/** ADR-0200-style: consent stays the live per-call check against consent-service, never cached here. */
interface ConsentCheckPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}

/**
 * ADR-0220 D3.5's materialised adverse-state set (was always empty until the
 * `LendingArrearsEventConsumer`/`PartyErasureConsumer` — see their KDoc for which of the four
 * [AdverseState] values are actually wired vs. still aspirational). Upsert-by-natural-key: a
 * state is either currently active or it isn't, there is no history to preserve here.
 */
interface AdverseStateRepository {
    suspend fun setActive(partyId: UUID, state: AdverseState, at: Instant)
    suspend fun clearActive(partyId: UUID, state: AdverseState)
    suspend fun activeStates(partyId: UUID): Set<AdverseState>
}

/** Latest placement wins within each closed app surface; there is no hidden cross-surface ranking. */
interface CampaignBannerPlacementRepository {
    suspend fun save(placement: CampaignBannerPlacement)
    suspend fun latestForPartyAndSlot(partyId: UUID, slot: SurfaceSlot): CampaignBannerPlacement?
    suspend fun belongsToPartyAtSlot(interactionRef: UUID, partyId: UUID, slot: SurfaceSlot): Boolean
}

/**
 * ADR-0220 D3 rule 2 — one current membership state per party, changed only via
 * `RewardsHubMembershipTransitions`. `current` returns `null` for a party who has never opted in
 * either way (the default is "opted out": `rewards_hub` is off by default), never a fabricated
 * `OptedOut` — the use case layer decides the default, this port only reports what is stored.
 */
interface RewardsHubMembershipRepository {
    suspend fun current(partyId: UUID): RewardsHubMembership?
    suspend fun save(membership: RewardsHubMembership)
}

/**
 * The durable [GamificationAward] ledger (query model) plus its transactional outbox write — same
 * split responsibility as `EngagementEventRepository`/`EngagementOutboxRepository`.
 */
interface GamificationAwardRepository {
    /**
     * Idempotency guard, keyed on (party, challenge) ALONE — not `correlationEventId`. A
     * challenge in this slice's catalogue is a one-time completion (e.g. finishing a course); two
     * separate `CONVERSION` posts for the same challenge are two attempts to report the SAME
     * real-world achievement, not two achievements, so the second must not award again even
     * though it carries a genuinely different triggering event id. Proven directly by
     * `GamificationOutboxIT`'s "posting the same conversion twice awards only once" — the first
     * version of this guard used `correlationEventId` in the key and that test caught it awarding
     * twice, because every HTTP POST creates a new `EngagementEvent` row with its own id.
     */
    suspend fun alreadyAwarded(partyId: UUID, challengeId: String): Boolean

    suspend fun save(award: GamificationAward)
}
