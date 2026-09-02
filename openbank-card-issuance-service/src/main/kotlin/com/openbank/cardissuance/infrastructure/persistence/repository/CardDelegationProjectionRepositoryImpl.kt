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
import org.hibernate.reactive.mutiny.Mutiny
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

    override suspend fun applyActive(grant: DelegatedCardGrant, lifecycleRevision: Long) {
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                claim(session, grant.id, lifecycleRevision, opening = true).chain { accepted ->
                    if (accepted == true) {
                        session.merge(CardDelegationProjectionEntity.fromDomain(grant, OffsetDateTime.now(clock)))
                            .replaceWithVoid()
                    } else {
                        io.smallrye.mutiny.Uni.createFrom().voidItem()
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun applyClosed(grantId: UUID, lifecycleRevision: Long?): Boolean = Panache.withTransaction {
        Panache.getSession().chain { session ->
            claim(session, grantId, lifecycleRevision, opening = false).chain { accepted ->
                if (accepted == true) {
                    update(
                        "active = false, updatedAt = ?1 where id = ?2 and active = true",
                        OffsetDateTime.now(clock),
                        grantId,
                    )
                        .replaceWith(true)
                } else {
                    io.smallrye.mutiny.Uni.createFrom().item(false)
                }
            }
        }
    }.awaitSuspending()

    private fun claim(session: Mutiny.Session, grantId: UUID, revision: Long?, opening: Boolean) = session
        .createNativeQuery(CLAIM_SQL, java.lang.Boolean::class.java)
        .setParameter("grantId", grantId)
        .setParameter("revision", revision)
        .setParameter("opening", opening)
        .singleResult

    override suspend fun findActiveByCardAndParty(cardId: UUID, partyId: UUID): List<DelegatedCardGrant> =
        Panache.withSession {
            find("cardId = ?1 and granteePartyId = ?2 and active = true", cardId, partyId)
                .list<CardDelegationProjectionEntity>()
        }.awaitSuspending().map { it.toDomain() }

    private companion object {
        const val CLAIM_SQL = "SELECT card_claim_delegation_lifecycle(:grantId, :revision, :opening)"
    }
}
