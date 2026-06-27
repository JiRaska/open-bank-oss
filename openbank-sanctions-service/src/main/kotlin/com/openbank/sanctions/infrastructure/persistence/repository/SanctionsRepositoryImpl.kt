// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.*
import com.openbank.sanctions.infrastructure.persistence.entity.SanctionsCheckEntity
import com.openbank.sanctions.infrastructure.persistence.mapper.*
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class SanctionsRepositoryImpl(private val outboxRepo: SanctionsOutboxRepository) :
    SanctionsRepository,
    PanacheRepository<SanctionsCheckEntity> {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun save(check: SanctionsCheck): SanctionsCheck {
        val e = check.toEntity()
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return e.toDomain()
    }

    override suspend fun saveWithEvent(check: SanctionsCheck, eventType: String): SanctionsCheck {
        val e = check.toEntity()
        val event = OutboxMessage(
            aggregateId = check.id,
            eventType = eventType,
            payload = mapper.writeValueAsString(check),
        )
        Panache.withTransaction { persist(e).chain { _ -> outboxRepo.persistInTransaction(event) } }.awaitSuspending()
        return e.toDomain()
    }

    override suspend fun findById(id: UUID) =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByIdempotencyKey(key: String) =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByStatus(status: SanctionsCheckStatus) =
        Panache.withSession { find("status", status).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun listChecks(): List<SanctionsCheck> =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }
}
