// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.infrastructure.persistence.entity.DelegationProjectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class DelegationProjectionRepositoryImpl(private val clock: Clock) :
    DelegationProjectionRepository,
    PanacheRepository<DelegationProjectionEntity> {

    // merge, not persist: application-assigned @Id + re-delivered activate/reinstate events —
    // merge is the idempotent upsert the projection needs.
    override suspend fun upsertActive(grant: DelegatedAccessGrant) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.merge(DelegationProjectionEntity.fromDomain(grant, OffsetDateTime.now(clock)))
            }
        }.awaitSuspending()
    }

    override suspend fun closeById(grantId: UUID): Boolean = Panache.withTransaction {
        update("active = false, updatedAt = ?1 where id = ?2 and active = true", OffsetDateTime.now(clock), grantId)
    }.awaitSuspending() > 0L

    override suspend fun findActiveByAccountAndParty(accountId: UUID, partyId: UUID): List<DelegatedAccessGrant> =
        findActiveByAccountPartyAndType(accountId, partyId, DelegatedAccessGrant.RESOURCE_TYPE_ACCOUNT)

    override suspend fun findActiveByAccount(accountId: UUID): List<DelegatedAccessGrant> = Panache.withSession {
        find(
            "accountId = ?1 and active = true and resourceType = ?2",
            accountId,
            DelegatedAccessGrant.RESOURCE_TYPE_ACCOUNT,
        ).list<DelegationProjectionEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByAccountPartyAndType(
        accountId: UUID,
        partyId: UUID,
        resourceType: String,
    ): List<DelegatedAccessGrant> = Panache.withSession {
        find(
            "accountId = ?1 and granteePartyId = ?2 and active = true and resourceType = ?3",
            accountId,
            partyId,
            resourceType,
        ).list<DelegationProjectionEntity>()
    }.awaitSuspending().map { it.toDomain() }
}
