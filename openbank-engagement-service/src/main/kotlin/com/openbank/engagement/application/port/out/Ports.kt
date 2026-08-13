// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.port.out

import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.SurfaceSlot
import java.time.Instant
import java.util.UUID

interface EngagementEventRepository {
    suspend fun save(event: EngagementEvent)

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
