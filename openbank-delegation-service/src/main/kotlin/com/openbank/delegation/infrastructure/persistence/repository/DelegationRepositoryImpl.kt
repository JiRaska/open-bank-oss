// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationConcurrentTransitionException
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
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

    override suspend fun save(grant: DelegationGrant, event: DomainEvent): DelegationGrant = Panache.withTransaction {
        val write = if (
            grant.status == DelegationStatus.OFFERED && grant.lifecycleRevision == INITIAL_LIFECYCLE_REVISION
        ) {
            persistNew(grant)
        } else {
            updateLifecycle(grant).replaceWith(grant)
        }
        write.flatMap { saved ->
            outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(saved)
        }
    }.awaitSuspending()

    private fun persistNew(grant: DelegationGrant): Uni<DelegationGrant> = Panache.getSession().flatMap { session ->
        session.persist(DelegationGrantEntity.fromDomain(grant)).replaceWith(grant)
    }

    /**
     * Compare-and-set only the lifecycle-owned mutable fields. Capabilities, parties and resource
     * are immutable after offer, so there is no reason to merge a detached full row. The V8 trigger
     * independently enforces the state graph and revision increment, including for an older writer
     * that is still rolling out and does not know this column.
     */
    private fun updateLifecycle(grant: DelegationGrant): Uni<Int> {
        val expectedRevision = grant.lifecycleRevision - 1
        if (expectedRevision < INITIAL_LIFECYCLE_REVISION) {
            return Uni.createFrom().failure(
                IllegalArgumentException("lifecycle transition must advance revision from zero"),
            )
        }
        return update(
            "status = ?1, lifecycleRevision = ?2, acceptScaSessionId = ?3, updatedAt = ?4, " +
                "closedAt = ?5, closedBy = ?6, closedReason = ?7 " +
                "where id = ?8 and lifecycleRevision = ?9",
            grant.status,
            grant.lifecycleRevision,
            grant.acceptScaSessionId,
            grant.updatedAt,
            grant.closedAt,
            grant.closedBy,
            grant.closedReason,
            grant.id,
            expectedRevision,
        ).flatMap { count ->
            if (count == 1) {
                Uni.createFrom().item(count)
            } else {
                Uni.createFrom().failure(
                    DelegationConcurrentTransitionException(grant.id, expectedRevision),
                )
            }
        }
    }

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

    override fun markExpired(
        id: UUID,
        expectedLifecycleRevision: Long,
        expiredAt: OffsetDateTime,
        event: DomainEvent,
    ): Uni<Boolean> = Panache.withTransaction {
        update(
            "status = 'EXPIRED', lifecycleRevision = lifecycleRevision + 1, " +
                "updatedAt = ?1, closedAt = ?1 where id = ?2 and status = 'ACTIVE' " +
                "and lifecycleRevision = ?3",
            expiredAt,
            id,
            expectedLifecycleRevision,
        ).flatMap { count ->
            if (count > 0) {
                outboxRepository.persistInTransaction(outboxMessage(event)).replaceWith(true)
            } else {
                Uni.createFrom().item(false)
            }
        }
    }

    private companion object {
        const val INITIAL_LIFECYCLE_REVISION = 0L
    }
}
