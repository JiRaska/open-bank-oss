// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import com.openbank.swift.infrastructure.persistence.mapper.applyUpdate
import com.openbank.swift.infrastructure.persistence.mapper.toDomain
import com.openbank.swift.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class SwiftRepositoryImpl(private val outboxRepoImpl: SwiftOutboxRepositoryImpl) :
    SwiftRepository,
    PanacheRepository<SwiftMessageEntity> {
    private fun mergeOrInsert(session: Mutiny.Session, msg: SwiftMessage): Uni<SwiftMessageEntity> =
        session.find(SwiftMessageEntity::class.java, msg.id).flatMap { existing ->
            if (existing != null) {
                existing.applyUpdate(msg)
                Uni.createFrom().item(existing)
            } else {
                session.merge(msg.toEntity())
            }
        }

    override suspend fun save(msg: SwiftMessage): SwiftMessage = Panache.withTransaction {
        Panache.getSession().flatMap { session -> mergeOrInsert(session, msg) }
    }.awaitSuspending().toDomain()

    override suspend fun saveWithOutbox(msg: SwiftMessage, outbox: SwiftOutboxMessage): SwiftMessage =
        Panache.withTransaction {
            Panache.getSession()
                .flatMap { session -> mergeOrInsert(session, msg) }
                .flatMap { entity -> outboxRepoImpl.persistWithinCurrentTransaction(outbox).replaceWith(entity) }
        }.awaitSuspending().toDomain()
    override suspend fun findById(id: UUID) =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByIdempotencyKey(key: String) =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun listAllMessages() = Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findByStatus(status: SwiftStatus) =
        Panache.withSession { find("status", status).list() }.awaitSuspending().map { it.toDomain() }
}
