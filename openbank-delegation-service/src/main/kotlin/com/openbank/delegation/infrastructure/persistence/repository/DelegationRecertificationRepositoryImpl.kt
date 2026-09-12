// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationConcurrentTransitionException
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.DelegationRecertificationRepository
import com.openbank.delegation.application.usecase.DelegationNotFoundException
import com.openbank.delegation.application.usecase.DelegationNotGrantorException
import com.openbank.delegation.application.usecase.DelegationRecertificationConflict
import com.openbank.delegation.domain.event.DelegationRecertificationConfirmed
import com.openbank.delegation.domain.event.DelegationRecertificationDue
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import com.openbank.delegation.domain.model.DelegationRecertificationStatus
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.delegation.infrastructure.persistence.entity.DelegationRecertificationEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class DelegationRecertificationRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : DelegationRecertificationRepository,
    PanacheRepository<DelegationRecertificationEntity> {

    /**
     * The grant row is the serialisation point. Locking only a missing cycle row would let two
     * schedulers both observe no row and race to insert; locking the stable grant also makes a
     * concurrent lifecycle change decide first.
     */
    override suspend fun createDueIfNeeded(grantId: UUID, now: OffsetDateTime): DelegationRecertificationCycle? =
        Panache.withTransaction {
            lockedGrant(grantId).flatMap { grant ->
                val audience = grant.recertificationAudience
                if (grant.status != DelegationStatus.ACTIVE || audience == null) {
                    return@flatMap Uni.createFrom().nullItem()
                }
                find(
                    "delegationId = ?1 and expectedLifecycleRevision = ?2 order by sequence desc",
                    grantId,
                    grant.lifecycleRevision,
                ).firstResult<DelegationRecertificationEntity>().flatMap { latest ->
                    if (latest?.status == DelegationRecertificationStatus.PENDING) {
                        return@flatMap Uni.createFrom().nullItem()
                    }
                    val base = latest?.confirmedAt ?: grant.updatedAt
                    val dueAt = audience.nextDueAt(base)
                    if (dueAt.isAfter(now)) {
                        return@flatMap Uni.createFrom().nullItem()
                    }
                    val cycle = DelegationRecertificationCycle(
                        delegationId = grant.id,
                        grantorPartyId = grant.grantorPartyId,
                        expectedLifecycleRevision = grant.lifecycleRevision,
                        audience = audience,
                        sequence = (latest?.sequence ?: 0) + 1,
                        dueAt = dueAt,
                        createdAt = now,
                    )
                    persist(DelegationRecertificationEntity.fromDomain(cycle))
                        .flatMap {
                            outboxRepository.persistInTransaction(
                                outboxMessage(
                                    DelegationRecertificationDue(
                                        aggregateId = grant.id,
                                        recertificationId = cycle.id,
                                        grantorPartyId = grant.grantorPartyId,
                                        expectedLifecycleRevision = grant.lifecycleRevision,
                                        audience = audience,
                                        occurredAt = now.toInstant(),
                                    ),
                                ),
                            )
                        }.replaceWith(cycle)
                }
            }
        }.awaitSuspending()

    override suspend fun listPendingByGrantor(grantorPartyId: UUID): List<DelegationRecertificationCycle> {
        val rows = Panache.withSession {
            find(
                "grantorPartyId = ?1 and status = ?2 order by dueAt asc",
                grantorPartyId,
                DelegationRecertificationStatus.PENDING,
            ).list<DelegationRecertificationEntity>()
        }.awaitSuspending()
        return rows.map { it.toDomain() }
    }

    override suspend fun confirm(
        recertificationId: UUID,
        grantorPartyId: UUID,
        now: OffsetDateTime,
    ): DelegationRecertificationCycle = Panache.withTransaction {
        lockedCycle(recertificationId).flatMap { row ->
            val cycle = row.toDomain()
            if (cycle.grantorPartyId != grantorPartyId) {
                return@flatMap Uni.createFrom().failure(
                    DelegationNotGrantorException(cycle.delegationId, grantorPartyId),
                )
            }
            lockedGrant(cycle.delegationId).flatMap { grant ->
                if (grant.status != DelegationStatus.ACTIVE ||
                    grant.lifecycleRevision != cycle.expectedLifecycleRevision
                ) {
                    return@flatMap Uni.createFrom().failure(
                        DelegationConcurrentTransitionException(
                            cycle.delegationId,
                            cycle.expectedLifecycleRevision,
                        ),
                    )
                }
                if (cycle.status != DelegationRecertificationStatus.PENDING) {
                    return@flatMap Uni.createFrom().failure(
                        DelegationRecertificationConflict("recertification cycle ${cycle.id} is already confirmed"),
                    )
                }
                val confirmed = cycle.confirm(grantorPartyId, now)
                row.status = confirmed.status
                row.confirmedAt = confirmed.confirmedAt
                row.confirmedBy = confirmed.confirmedBy
                outboxRepository.persistInTransaction(
                    outboxMessage(
                        DelegationRecertificationConfirmed(
                            aggregateId = cycle.delegationId,
                            recertificationId = cycle.id,
                            grantorPartyId = grantorPartyId,
                            expectedLifecycleRevision = cycle.expectedLifecycleRevision,
                            occurredAt = now.toInstant(),
                        ),
                    ),
                ).replaceWith(confirmed)
            }
        }
    }.awaitSuspending()

    private fun lockedGrant(id: UUID): Uni<DelegationGrantEntity> = Panache.getSession().flatMap { session ->
        session.find(DelegationGrantEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE)
    }.flatMap { grant ->
        if (grant == null) Uni.createFrom().failure(DelegationNotFoundException(id)) else Uni.createFrom().item(grant)
    }

    private fun lockedCycle(id: UUID): Uni<DelegationRecertificationEntity> = Panache.getSession().flatMap { session ->
        session.find(DelegationRecertificationEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE)
    }.flatMap { cycle ->
        if (cycle == null) Uni.createFrom().failure(DelegationNotFoundException(id)) else Uni.createFrom().item(cycle)
    }

    private fun outboxMessage(event: DomainEvent) = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )
}
