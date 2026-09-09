// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.ApprovalGroupConcurrentUpdateException
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.delegation.infrastructure.persistence.entity.ApprovalGroupCommandEntity
import com.openbank.delegation.infrastructure.persistence.entity.ApprovalGroupEntity
import com.openbank.delegation.infrastructure.persistence.entity.ApprovalGroupRevisionEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.LockMode
import java.util.UUID

@ApplicationScoped
class ApprovalGroupRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : ApprovalGroupRepository,
    PanacheRepository<ApprovalGroupEntity> {

    override suspend fun create(group: ApprovalGroup, event: DomainEvent): ApprovalGroup = Panache.withTransaction {
        Panache.getSession()
            .flatMap { session -> session.persist(ApprovalGroupEntity.fromDomain(group)) }
            .flatMap { Panache.getSession() }
            .flatMap { session -> session.persist(ApprovalGroupRevisionEntity.fromDomain(group)) }
            .flatMap { Panache.getSession() }
            .flatMap { session -> session.persist(ApprovalGroupCommandEntity.fromDomain(group)) }
            .flatMap { outboxRepository.persistInTransaction(event.toOutbox()).replaceWith(group) }
    }.awaitSuspending()

    override suspend fun update(group: ApprovalGroup, expectedRevision: Long, event: DomainEvent): ApprovalGroup =
        Panache.withTransaction {
            Panache.getSession()
                .flatMap { it.find(ApprovalGroupEntity::class.java, group.id, LockMode.PESSIMISTIC_WRITE) }
                .flatMap { entity ->
                    if (entity == null || entity.revision != expectedRevision) {
                        return@flatMap io.smallrye.mutiny.Uni.createFrom().failure(
                            ApprovalGroupConcurrentUpdateException(group.id, expectedRevision),
                        )
                    }
                    entity.groupName = group.name
                    entity.members.clear()
                    entity.members.addAll(group.members)
                    entity.threshold = group.threshold
                    entity.revision = group.revision
                    entity.active = group.active
                    entity.lastScaSessionId = group.lastScaSessionId
                    entity.updatedAt = group.updatedAt
                    Panache.getSession().flatMap { it.flush() }
                        .flatMap { Panache.getSession() }
                        .flatMap { session -> session.persist(ApprovalGroupRevisionEntity.fromDomain(group)) }
                        .flatMap { Panache.getSession() }
                        .flatMap { session -> session.persist(ApprovalGroupCommandEntity.fromDomain(group)) }
                        .flatMap { outboxRepository.persistInTransaction(event.toOutbox()) }
                        .replaceWith(group)
                }
        }.awaitSuspending()

    override suspend fun findById(id: UUID): ApprovalGroup? = Panache.withSession {
        find("groupId", id).firstResult<ApprovalGroupEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByScaSessionId(scaSessionId: UUID): ApprovalGroup? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.find(ApprovalGroupCommandEntity::class.java, scaSessionId)
        }
    }.awaitSuspending()?.toDomain()

    override suspend fun findByOwner(ownerPartyId: UUID): List<ApprovalGroup> = Panache.withSession {
        find("ownerPartyId = ?1 order by groupName", ownerPartyId).list<ApprovalGroupEntity>()
    }.awaitSuspending().map { it.toDomain() }

    private fun DomainEvent.toOutbox() = OutboxMessage(
        aggregateId = aggregateId,
        eventType = eventType,
        payload = objectMapper.writeValueAsString(this),
        createdAt = occurredAt,
    )
}
