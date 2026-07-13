// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.infrastructure.persistence.entity.SignatureCeremonyEntity
import com.openbank.document.infrastructure.persistence.mapper.toDomain
import com.openbank.document.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import java.util.UUID

@ApplicationScoped
class CeremonyRepositoryImpl :
    CeremonyRepositoryPort,
    PanacheRepository<SignatureCeremonyEntity> {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var outboxRepo: DocumentOutboxRepositoryImpl

    // `merge()`, not find-then-mutate: recordDecision reads a ceremony, mutates it in memory, then
    // saves it in a LATER, separate transaction — a find() done fresh inside THIS transaction would
    // always see the current row and could never detect that the original read (back in the
    // use-case) is now stale, defeating @Version entirely (a real bug, found in review before this
    // shipped). merge() takes the entity carrying the version READ AT USE-CASE TIME and lets
    // Hibernate compare it against the DB row's current version, throwing OptimisticLockException
    // on a genuine conflict — for a NEW ceremony (version 0, no existing row) merge() inserts, same
    // as persist() would have. Precedent: ScaChallengeRepositoryImpl.save().
    override suspend fun save(ceremony: SignatureCeremony): SignatureCeremony = Panache.withTransaction {
        getSession().flatMap { s -> s.merge(ceremony.toEntity(objectMapper)) }
    }.onFailure(OptimisticLockException::class.java).transform { conflictException(ceremony.id) }
        .map { it.toDomain(objectMapper) }
        .awaitSuspending()

    override suspend fun findById(id: UUID): SignatureCeremony? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun saveWithOutbox(ceremony: SignatureCeremony, outboxMessage: OutboxMessage): SignatureCeremony =
        Panache.withTransaction {
            getSession().flatMap { s -> s.merge(ceremony.toEntity(objectMapper)) }
                .call { _ -> outboxRepo.persistInTransaction(outboxMessage) }
        }.onFailure(OptimisticLockException::class.java).transform { conflictException(ceremony.id) }
            .map { it.toDomain(objectMapper) }
            .awaitSuspending()

    // Surfaced as IllegalStateException so the shared libs-runtime IllegalStateExceptionMapper
    // maps it to 422 instead of a raw 500 — a concurrent decision on the same ceremony is a
    // client-actionable conflict (retry), not a server fault.
    private fun conflictException(ceremonyId: UUID) =
        IllegalStateException("Ceremony $ceremonyId was concurrently modified — retry the decision")
}
