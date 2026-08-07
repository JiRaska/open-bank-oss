// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.application.port.out.ConsentOutboxRepository
import com.openbank.consent.application.port.out.SuppressionRepository
import com.openbank.consent.domain.model.Suppression
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import com.openbank.consent.infrastructure.persistence.entity.SuppressionEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class SuppressionRepositoryImpl(
    private val outboxRepository: ConsentOutboxRepository,
    private val objectMapper: ObjectMapper,
) : SuppressionRepository,
    PanacheRepository<SuppressionEntity> {

    override suspend fun save(suppression: Suppression, event: DomainEvent): Suppression = Panache.withTransaction {
        // Aggregate + outbox row in ONE transaction (transactional outbox, ADR-0126 §D3) — the
        // gate's near-real-time invalidation signal is durable iff the suppression commits.
        Panache.getSession().flatMap { session -> session.merge(suppression.toEntity()) }
            .flatMap { merged -> outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(merged) }
    }.awaitSuspending().let { suppression }

    override suspend fun update(suppression: Suppression, event: DomainEvent): Suppression = save(suppression, event)

    override suspend fun findById(id: UUID): Suppression? =
        Panache.withSession { find("id", id).firstResult<SuppressionEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findActiveByParty(partyId: UUID): List<Suppression> = Panache.withSession {
        find("partyId = ?1 and revokedAt is null", partyId).list<SuppressionEntity>()
    }.awaitSuspending().map { it.toDomain() }

    private fun outboxMessage(event: DomainEvent): OutboxMessage = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )
}

private fun Suppression.toEntity(): SuppressionEntity = SuppressionEntity().apply {
    id = this@toEntity.id
    partyId = this@toEntity.partyId
    scope = this@toEntity.scope.name
    value = this@toEntity.value
    reasonCode = this@toEntity.reason.name
    source = this@toEntity.source
    createdBy = this@toEntity.createdBy
    createdAt = this@toEntity.createdAt
    revokedAt = this@toEntity.revokedAt
    revokedBy = this@toEntity.revokedBy
}

private fun SuppressionEntity.toDomain(): Suppression = Suppression(
    id = id,
    partyId = partyId,
    scope = SuppressionScope.valueOf(scope),
    value = value,
    reason = SuppressionReason.valueOf(reasonCode),
    source = source,
    createdBy = createdBy,
    createdAt = createdAt,
    revokedAt = revokedAt,
    revokedBy = revokedBy,
)
