// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.repository

import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.infrastructure.persistence.entity.ConsentEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ConsentRepositoryImpl :
    ConsentRepository,
    PanacheRepository<ConsentEntity> {

    override suspend fun save(consent: Consent): Consent {
        val entity = ConsentEntity.fromDomain(consent)
        Panache.withTransaction { persistAndFlush(entity) }.awaitSuspending()
        return entity.toDomain()
    }

    override suspend fun findById(id: UUID): Consent? =
        Panache.withSession { find("id", id).firstResult<ConsentEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<Consent> =
        Panache.withSession { find("partyId", partyId).list<ConsentEntity>() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByGranteeId(granteeId: String): List<Consent> = Panache.withSession {
        find("granteeId", granteeId).list<ConsentEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByGranteeAndParty(granteeId: String, partyId: UUID): List<Consent> =
        Panache.withSession {
            find("granteeId = ?1 and partyId = ?2 and status = 'ACTIVE'", granteeId, partyId)
                .list<ConsentEntity>()
        }.awaitSuspending().map { it.toDomain() }
}
