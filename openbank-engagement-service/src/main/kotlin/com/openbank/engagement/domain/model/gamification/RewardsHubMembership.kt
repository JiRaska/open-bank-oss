// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model.gamification

import java.time.Instant
import java.util.UUID

/**
 * ADR-0220 D3 rule 2 — `rewards_hub` is opt-in (off by default) and "leaving is one tap and keeps
 * earned value". Modelled as a sealed domain STATE with two members rather than a `Boolean`
 * flag, so the transition is an explicit domain operation
 * ([RewardsHubMembershipTransitions.optIn]/[optOut]) and not a field a caller can flip in place —
 * there is no setter on [partyId] or on the state itself, and constructing a member directly
 * outside this file is the only way to "skip" the transition object, which is exactly as visible
 * in review as it sounds.
 *
 * "Keeps earned value": [OptedOut] carries no reference to, and this type performs no mutation
 * of, any [Points]/[Badge]/[GamificationAward] ledger — opting out changes ONLY whether the party
 * is targeted for new personalised challenges (`EvaluateChallengeTargetingUseCase`); it is
 * structurally incapable of touching what has already been earned, because nothing in this file
 * has a handle on the award ledger to touch.
 */
sealed class RewardsHubMembership {
    abstract val partyId: UUID
    abstract val since: Instant

    data class OptedIn(override val partyId: UUID, override val since: Instant) : RewardsHubMembership()
    data class OptedOut(override val partyId: UUID, override val since: Instant) : RewardsHubMembership()
}

/**
 * The only sanctioned way to change a party's [RewardsHubMembership]. Both functions take the
 * CURRENT state (or none) and hand back a brand-new value — opting out and then reading the
 * result back as opted-in again requires a second, equally explicit call to [optIn]; there is no
 * path that silently reverts a stored [RewardsHubMembership.OptedOut] to [RewardsHubMembership.OptedIn]
 * as a side effect of anything else in this package.
 */
object RewardsHubMembershipTransitions {
    fun optIn(partyId: UUID, at: Instant): RewardsHubMembership.OptedIn = RewardsHubMembership.OptedIn(partyId, at)

    fun optOut(partyId: UUID, at: Instant): RewardsHubMembership.OptedOut = RewardsHubMembership.OptedOut(partyId, at)
}
