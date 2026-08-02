// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class DelegationRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : DelegationRepository,
    PanacheRepository<DelegationGrantEntity> {

    // merge, not persist: the aggregate carries an application-assigned @Id, so persist()
    // schedules an INSERT even for an existing row and fails on the PK for every lifecycle
    // transition (accept/revoke/suspend). merge is the upsert the transitions need.
    override suspend fun save(grant: DelegationGrant): DelegationGrant =
        Panache.withTransaction { mergeGrant(grant) }.awaitSuspending().toDomain()

    override suspend fun save(grant: DelegationGrant, event: DomainEvent): DelegationGrant = Panache.withTransaction {
        mergeGrant(grant).flatMap { merged ->
            outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(merged)
        }
    }.awaitSuspending().toDomain()

    private fun mergeGrant(grant: DelegationGrant): Uni<DelegationGrantEntity> =
        Panache.getSession().flatMap { session -> session.merge(DelegationGrantEntity.fromDomain(grant)) }

    private fun outboxMessage(event: DomainEvent): OutboxMessage = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )

    override suspend fun findById(id: UUID): DelegationGrant? =
        Panache.withSession { find("id", id).firstResult<DelegationGrantEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findByGrantorId(grantorPartyId: UUID): List<DelegationGrant> =
        Panache.withSession { find("grantorPartyId", grantorPartyId).list<DelegationGrantEntity>() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun findByGranteeId(granteePartyId: UUID): List<DelegationGrant> =
        Panache.withSession { find("granteePartyId", granteePartyId).list<DelegationGrantEntity>() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun findActiveByGranteeAndResource(
        granteePartyId: UUID,
        resourceType: DelegationResourceType,
        resourceId: UUID,
    ): List<DelegationGrant> = Panache.withSession {
        find(
            "granteePartyId = ?1 and resourceType = ?2 and resourceId = ?3 and status = 'ACTIVE'",
            granteePartyId,
            resourceType,
            resourceId,
        ).list<DelegationGrantEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override fun findExpiredActive(threshold: OffsetDateTime): Uni<List<DelegationGrant>> = Panache.withSession {
        find("status = 'ACTIVE' and validTo is not null and validTo < ?1", threshold)
            .list<DelegationGrantEntity>()
    }.map { list -> list.map { it.toDomain() } }

    override fun markExpired(id: UUID, expiredAt: OffsetDateTime, event: DomainEvent): Uni<Boolean> =
        Panache.withTransaction {
            update(
                "status = 'EXPIRED', updatedAt = ?1, closedAt = ?1 where id = ?2 and status = 'ACTIVE'",
                expiredAt,
                id,
            ).flatMap { count ->
                if (count > 0L) {
                    outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(true)
                } else {
                    Uni.createFrom().item(false)
                }
            }
        }
}
