// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.PartyMandateProjectionRepository
import com.openbank.account.domain.model.PartyMandateProjection
import com.openbank.account.infrastructure.persistence.entity.PartyMandateProjectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PartyMandateProjectionRepositoryImpl :
    PartyMandateProjectionRepository,
    PanacheRepository<PartyMandateProjectionEntity> {

    override suspend fun upsert(mandate: PartyMandateProjection) {
        Panache.withTransaction {
            Panache.getSession().flatMap { it.merge(PartyMandateProjectionEntity.fromDomain(mandate)) }
        }.awaitSuspending()
    }

    override suspend fun revoke(mandateId: UUID) {
        Panache.withTransaction { update("active = false where mandateId = ?1", mandateId) }.awaitSuspending()
    }

    override suspend fun findActive(principalPartyId: UUID, agentPartyId: UUID): List<PartyMandateProjection> =
        Panache.withSession {
            find(
                "principalPartyId = ?1 and agentPartyId = ?2 and active = true",
                principalPartyId,
                agentPartyId,
            ).list<PartyMandateProjectionEntity>()
        }.awaitSuspending().map { it.toDomain() }
}
