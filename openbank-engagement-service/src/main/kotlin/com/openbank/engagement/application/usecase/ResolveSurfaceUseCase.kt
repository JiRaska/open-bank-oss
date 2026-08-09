// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.AdverseStateRepository
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

        // Three of four adverse states are materialised (issue #2749): ARREARS
        // (LendingArrearsEventConsumer, openbank.lending.events), ERASURE_REQUESTED
        // (PartyErasureConsumer, openbank.party.events) and FRAUD_HOLD (FraudHoldEventConsumer,
        // fraud-hold-events-in). DISPUTE_OPENED is the one gap: a party with an open dispute is
        // NOT currently excluded.
        //
        // The previous version of this note was wrong on all three of its factual claims, and is
        // corrected rather than deleted so the next reader learns it was superseded rather than
        // that it never existed. It said neither signal is published anywhere in the fleet,
        // "fraud-service has no persisted hold state" and "dispute-service emits only on
        // resolution, never on open". Measured on this sha: fraud-service persists holds
        // (FraudHoldEntity/FraudHoldService) and dispute-service does emit `dispute.opened`
        // (DisputeService.openedOutboxMessage, landed #4087).
        //
        // So the REASON DISPUTE_OPENED is missing is not the producer — it exists — but that this
        // service has no @Incoming for openbank.dispute.events. A note that blames the wrong layer
        // sends the next person to fix a service that is already correct.
        val eligibility = EligibilitySnapshot(
            partyId = partyId,
            adverseState = adverseState.activeStates(partyId),
            asOf = Instant.now(),
        )
        return Result.Rendered(SurfaceResolver.resolve(slot, eligibility))
    }

    companion object {
        /** Matches the enum literal on `openbank-consent-service`'s `ConsentScope` (ADR-0220 D5). */
        const val MARKETING_COMMS_INAPP_SCOPE = "MARKETING_COMMS_INAPP"

        /** How far back to look for a dismissal streak. `DismissalRule` needs enough history to
         *  see three consecutive dismissals; a week is generous without scanning unbounded rows. */
        val DISMISSAL_LOOKBACK: Duration = Duration.ofDays(7)
    }
}
