// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.engagement.domain.model.DismissalRule
import com.openbank.engagement.domain.model.EligibilitySnapshot
import com.openbank.engagement.domain.model.SurfaceContent
import com.openbank.engagement.domain.model.SurfaceResolver
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactGateDecision
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.MarketingCallSite
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-0220 D1 minus the pieces this slice does not build: eligibility snapshot materialisation is
 * now real for two of the four [com.openbank.engagement.domain.model.AdverseState] values (see
 * the inline comment below), and NBA ranking stays out of scope, blocked on ADR-0201 D5
 * (unchanged from the domain layer's own [SurfaceResolver] doc).
 */
@ApplicationScoped
class ResolveSurfaceUseCase(
    private val contactGate: ContactPolicyGate,
    private val events: EngagementEventRepository,
    private val adverseState: AdverseStateRepository,
    private val banners: CampaignBannerPlacementRepository,
) {

    sealed interface Result {
        data class Rendered(val content: List<SurfaceContent>) : Result
        data class NotEligible(val reason: ContactGateDecision) : Result

        /** Distinct from [NotEligible]: this party is consented and under cap, but told the
         *  platform to stop via repeated dismissal (D2) — a local exclusion, not a gate denial. */
        object Suppressed : Result
    }

    @MarketingCallSite
    suspend fun resolve(partyId: UUID, slot: SurfaceSlot): Result {
        val decision = contactGate.check(
            partyId = partyId,
            contactClass = ContactClass.PROMOTIONAL_IMPRESSION,
            consentScope = MARKETING_COMMS_INAPP_SCOPE,
            topic = slot.name,
        )
        if (!decision.allowed) return Result.NotEligible(decision)

        val recent = events.recentForPartyAndSlot(partyId, slot, Instant.now().minus(DISMISSAL_LOOKBACK))
        if (DismissalRule.shouldSuppress(recent)) return Result.Suppressed

        // WHICH adverse states are materialised is NOT restated here (issue #2749). The answer is
        // the set of @Incoming consumers in this service's infrastructure/kafka package, and
        // AdverseState's own KDoc in Eligibility.kt tracks it. A second copy of that list in a
        // comment is what went stale last time, and would go stale again the moment a consumer is
        // added — which is exactly what #4297 does.
        //
        // What is worth keeping in place, because it is timeless and a deletion would erase it:
        // the note that used to stand here was wrong on all three of its factual claims. It said
        // neither the fraud nor the dispute signal is published anywhere in the fleet, that
        // "fraud-service has no persisted hold state", and that "dispute-service emits only on
        // resolution, never on open". Measured 2026-08-09: fraud-service persists holds
        // (FraudHoldEntity/FraudHoldService), and dispute-service emits `dispute.opened`
        // (DisputeService.openedOutboxMessage, landed #4087).
        //
        // The lesson that survives the fix: a signal missing HERE is not evidence the producer is
        // missing. That note blamed the producing services, and a reader acting on it would have
        // gone to fix two services that were already correct. The gap was always the consumer end.
        val eligibility = EligibilitySnapshot(
            partyId = partyId,
            adverseState = adverseState.activeStates(partyId),
            asOf = Instant.now(),
        )
        val catalogue = SurfaceResolver.resolve(slot, eligibility)
        val campaignBanner = if (slot == SurfaceSlot.HOME_BANNER) banners.latestForParty(partyId) else null
        // The home slot intentionally has one campaign surface, with no opaque score or rotation.
        // A current campaign placement comes before the generic catalogue fallback.
        return Result.Rendered(listOfNotNull(campaignBanner?.toSurfaceContent()) + catalogue)
    }

    companion object {
        /** Matches the enum literal on `openbank-consent-service`'s `ConsentScope` (ADR-0220 D5). */
        const val MARKETING_COMMS_INAPP_SCOPE = "MARKETING_COMMS_INAPP"

        /** How far back to look for a dismissal streak. `DismissalRule` needs enough history to
         *  see three consecutive dismissals; a week is generous without scanning unbounded rows. */
        val DISMISSAL_LOOKBACK: Duration = Duration.ofDays(7)
    }
}
