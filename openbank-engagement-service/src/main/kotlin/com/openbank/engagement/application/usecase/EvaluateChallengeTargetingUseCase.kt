// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.domain.model.EligibilityRule
import com.openbank.engagement.domain.model.EligibilitySnapshot
import com.openbank.engagement.domain.model.gamification.ChallengeCatalog
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactGateDecision
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.MarketingCallSite
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * ADR-0220 D3 rule 2/5 — decides whether a party may be proactively INVITED into a new
 * personalised challenge. This is the D3 marketing touch (`@MarketingCallSite`, wired exactly like
 * `ResolveSurfaceUseCase`): "personalised challenges additionally require `MARKETING_COMMS_INAPP`
 * consent, and consent wins every conflict", enforced through the same [ContactPolicyGate] every
 * other marketing-class touch in this service goes through — never a second, parallel gate.
 *
 * Deliberately separate from [AwardGamificationPointsUseCase]: that use case pays out a reward for
 * something a party already did; this one decides whether to reach out and suggest something new.
 * Only the second is a marketing touch under ADR-0219 D4's definition.
 */
@ApplicationScoped
class EvaluateChallengeTargetingUseCase(
    private val contactGate: ContactPolicyGate,
    private val membership: RewardsHubMembershipRepository,
    private val adverseState: AdverseStateRepository,
) {
    sealed interface Result {
        object Eligible : Result
        data class NotEligible(val reason: String) : Result
    }

    @MarketingCallSite
    suspend fun evaluate(partyId: UUID, challengeId: String): Result {
        if (!ChallengeCatalog.exists(challengeId)) return Result.NotEligible("unknown challenge")

        // D3 rule 2: the hub visibility toggle gates whether the party is targeted at all.
        if (membership.current(partyId) !is RewardsHubMembership.OptedIn) {
            return Result.NotEligible("not opted in to rewards hub")
        }

        // D3 rule 5: vulnerable-customer exclusion, reusing the SAME EligibilitySnapshot contract
        // ResolveSurfaceUseCase uses (ADR-0220 D1) — never a second, gamification-local copy of
        // the adverse-state rule.
        val eligibility = EligibilitySnapshot(
            partyId = partyId,
            adverseState = adverseState.activeStates(partyId),
            asOf = Instant.now(),
        )
        if (!EligibilityRule.isEligibleForPromotionalTargeting(eligibility)) {
            return Result.NotEligible("vulnerable-customer targeting exclusion")
        }

        val decision = contactGate.check(
            partyId = partyId,
            contactClass = ContactClass.PROMOTIONAL_IMPRESSION,
            consentScope = ResolveSurfaceUseCase.MARKETING_COMMS_INAPP_SCOPE,
            topic = challengeId,
        )
        return if (decision.allowed) Result.Eligible else Result.NotEligible(gateDenyReason(decision))
    }

    private fun gateDenyReason(decision: ContactGateDecision): String = decision.denyReason?.name ?: "GATE_DENIED"
}
