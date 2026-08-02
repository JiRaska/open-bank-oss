// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.repository

import com.openbank.cardissuance.application.port.out.CardDelegationProjectionRepository
import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import com.openbank.cardissuance.infrastructure.persistence.entity.CardDelegationProjectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class CardDelegationProjectionRepositoryImpl(private val clock: Clock) :
    CardDelegationProjectionRepository,
    PanacheRepository<CardDelegationProjectionEntity> {

    // merge, not persist: application-assigned @Id + re-delivered activate/reinstate events.
    override suspend fun upsertActive(grant: DelegatedCardGrant) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.merge(CardDelegationProjectionEntity.fromDomain(grant, OffsetDateTime.now(clock)))
            }
        }.awaitSuspending()
    }

    override suspend fun closeById(grantId: UUID): Boolean = Panache.withTransaction {
        update("active = false, updatedAt = ?1 where id = ?2 and active = true", OffsetDateTime.now(clock), grantId)
    }.awaitSuspending() > 0L

    override suspend fun findActiveByCardAndParty(cardId: UUID, partyId: UUID): List<DelegatedCardGrant> =
        Panache.withSession {
            find("cardId = ?1 and granteePartyId = ?2 and active = true", cardId, partyId)
                .list<CardDelegationProjectionEntity>()
        }.awaitSuspending().map { it.toDomain() }
}
