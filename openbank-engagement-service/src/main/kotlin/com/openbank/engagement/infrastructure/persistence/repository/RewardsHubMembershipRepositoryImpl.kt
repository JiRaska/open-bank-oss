// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.repository

import com.openbank.engagement.application.port.out.RewardsHubMembershipRepository
import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import com.openbank.engagement.infrastructure.persistence.entity.RewardsHubMembershipEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class RewardsHubMembershipRepositoryImpl :
    RewardsHubMembershipRepository,
    PanacheRepository<RewardsHubMembershipEntity> {

    override suspend fun current(partyId: UUID): RewardsHubMembership? = Panache.withSession {
        find("partyId", partyId).firstResult()
    }.map { it?.toDomain() }.awaitSuspending()

    /**
     * Find-then-merge on the `party_id` primary key — never a naked `persist()`, for the same
     * assigned-id/INSERT-only reason `AdverseStateRepositoryImpl` documents: a second `optIn`/
     * `optOut` call for a party who already has a row would otherwise 500 at flush instead of
     * recording the new transition.
     */
    override suspend fun save(membership: RewardsHubMembership) {
        Panache.withTransaction {
            find("partyId", membership.partyId).firstResult().chain { existing ->
                val entity = RewardsHubMembershipEntity.from(membership)
                if (existing == null) {
                    persist(entity)
                } else {
                    Panache.getSession().flatMap { it.merge(entity) }
                }
            }
        }.awaitSuspending()
    }
}
