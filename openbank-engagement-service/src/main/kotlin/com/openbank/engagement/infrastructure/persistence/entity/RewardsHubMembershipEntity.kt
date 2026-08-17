// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * `party_id` is the natural key AND the `@Id` here (unlike `AdverseStateEntity`'s surrogate id):
 * membership is a single current-state row per party — there is exactly one, never a history of
 * them — so an application-assigned id on the natural key is safe from this repo's own
 * assigned-id/INSERT-only pitfall as long as the repository always finds-then-merges rather than
 * blindly `persist()`-ing (see `RewardsHubMembershipRepositoryImpl`).
 */
@Entity
@Table(name = "rewards_hub_membership")
class RewardsHubMembershipEntity : PanacheEntityBase() {
    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var state: String

    @Column(nullable = false)
    lateinit var since: Instant

    fun toDomain(): RewardsHubMembership = when (state) {
        STATE_OPTED_IN -> RewardsHubMembership.OptedIn(partyId, since)
        STATE_OPTED_OUT -> RewardsHubMembership.OptedOut(partyId, since)
        else -> error("unknown rewards_hub_membership state '$state' for party $partyId")
    }

    companion object {
        const val STATE_OPTED_IN = "OPTED_IN"
        const val STATE_OPTED_OUT = "OPTED_OUT"

        fun from(membership: RewardsHubMembership): RewardsHubMembershipEntity {
            val entity = RewardsHubMembershipEntity()
            entity.partyId = membership.partyId
            entity.since = membership.since
            entity.state = when (membership) {
                is RewardsHubMembership.OptedIn -> STATE_OPTED_IN
                is RewardsHubMembership.OptedOut -> STATE_OPTED_OUT
            }
            return entity
        }
    }
}
