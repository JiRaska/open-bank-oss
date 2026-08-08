// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.repository

import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.infrastructure.persistence.entity.AdverseStateEntity
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AdverseStateRepositoryImpl :
    AdverseStateRepository,
    PanacheRepository<AdverseStateEntity> {

    /**
     * Find-then-persist-or-update on the (party_id, state) business key — never a naked
     * `persist()` on a fresh entity when one might already exist, per this repo's own
     * assigned-id/INSERT-only pitfall (see the entity's KDoc).
     */
    override suspend fun setActive(partyId: UUID, state: AdverseState, at: Instant) {
        Panache.withTransaction {
            find("partyId = ?1 and state = ?2", partyId, state.name).firstResult()
                .chain { existing ->
                    if (existing != null) {
                        existing.setAt = at
                        Panache.getSession().flatMap { it.merge(existing) }
                    } else {
                        val entity = AdverseStateEntity()
                        entity.id = Ids.newId()
                        entity.partyId = partyId
                        entity.state = state.name
                        entity.setAt = at
                        persist(entity)
                    }
                }
        }.awaitSuspending()
    }

    override suspend fun clearActive(partyId: UUID, state: AdverseState) {
        Panache.withTransaction {
            delete("partyId = ?1 and state = ?2", partyId, state.name)
        }.awaitSuspending()
    }

    override suspend fun activeStates(partyId: UUID): Set<AdverseState> = Panache.withSession {
        find("partyId = ?1", partyId).list()
    }.map { entities -> entities.map { AdverseState.valueOf(it.state) }.toSet() }.awaitSuspending()
}
