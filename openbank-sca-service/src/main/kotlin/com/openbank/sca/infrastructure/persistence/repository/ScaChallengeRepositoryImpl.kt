// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.repository

import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaConcurrentUpdateException
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.infrastructure.persistence.entity.ScaChallengeEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class ScaChallengeRepositoryImpl :
    ScaChallengeRepository,
    PanacheRepository<ScaChallengeEntity> {

    @Inject
    lateinit var clock: Clock

    override suspend fun save(challenge: ScaChallenge): ScaChallenge {
        val entity = ScaChallengeEntity.fromDomain(challenge)
        val merged = Panache.withTransaction {
            getSession().flatMap { s -> s.merge(entity) }
        }.onFailure(OptimisticLockException::class.java).transform { ScaConcurrentUpdateException(challenge.id) }
            .awaitSuspending()
        return merged.toDomain()
    }

    override suspend fun findById(id: UUID): ScaChallenge? =
        Panache.withSession { find("id", id).firstResult<ScaChallengeEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findPendingByParty(partyId: UUID): List<ScaChallenge> = Panache.withSession {
        find(
            "partyId = ?1 and status = ?2 and expiresAt > ?3 order by createdAt desc",
            partyId,
            ScaStatus.PENDING,
            OffsetDateTime.now(clock),
        ).list<ScaChallengeEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun markConsumed(id: UUID): Boolean = Panache.withTransaction {
        update(
            "consumedAt = ?1, version = version + 1 where id = ?2 and consumedAt is null " +
                "and status = ?3 and expiresAt > ?1",
            OffsetDateTime.now(clock),
            id,
            ScaStatus.COMPLETED,
        )
    }.awaitSuspending() == 1
}
