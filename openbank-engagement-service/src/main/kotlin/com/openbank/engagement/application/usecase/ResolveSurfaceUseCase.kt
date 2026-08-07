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

        // Half-real (issue #2749): ARREARS (LendingArrearsEventConsumer, openbank.lending.events)
        // and ERASURE_REQUESTED (PartyErasureConsumer, openbank.party.events) are materialised.
        // FRAUD_HOLD and DISPUTE_OPENED are NOT — neither signal is published as an event
        // anywhere in this fleet today (fraud-service has no persisted hold state; dispute-service
        // emits only on resolution, never on open). That is still an honest gap for those two, not
        // a silent one — a party with an open dispute or fraud hold is NOT currently excluded.
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
