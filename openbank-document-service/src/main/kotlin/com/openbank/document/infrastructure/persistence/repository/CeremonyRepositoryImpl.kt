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
import io.smallrye.mutiny.Uni
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

    override suspend fun save(ceremony: SignatureCeremony): SignatureCeremony {
        Panache.withTransaction {
            find("id", ceremony.id).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.applyFrom(ceremony)
                    Uni.createFrom().item(existing)
                } else {
                    persist(ceremony.toEntity(objectMapper))
                }
            }
        }.onFailure(OptimisticLockException::class.java).transform { conflictException(ceremony.id) }
            .awaitSuspending()
        return ceremony
    }

    override suspend fun findById(id: UUID): SignatureCeremony? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun saveWithOutbox(ceremony: SignatureCeremony, outboxMessage: OutboxMessage): SignatureCeremony =
        Panache.withTransaction {
            find("id", ceremony.id).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.applyFrom(ceremony)
                    outboxRepo.persistInTransaction(outboxMessage).replaceWith(ceremony)
                } else {
                    persist(ceremony.toEntity(objectMapper))
                        .chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
                        .replaceWith(ceremony)
                }
            }
        }.onFailure(OptimisticLockException::class.java).transform { conflictException(ceremony.id) }
            .awaitSuspending()

    // Surfaced as IllegalStateException so the shared libs-runtime IllegalStateExceptionMapper
    // maps it to 422 instead of a raw 500 — a concurrent decision on the same ceremony is a
    // client-actionable conflict (retry), not a server fault.
    private fun conflictException(ceremonyId: UUID) =
        IllegalStateException("Ceremony $ceremonyId was concurrently modified — retry the decision")

    private fun SignatureCeremonyEntity.applyFrom(ceremony: SignatureCeremony) {
        status = ceremony.status
        signersJson = objectMapper.writeValueAsString(ceremony.signers)
    }
}
