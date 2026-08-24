// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.repository

import com.openbank.dispute.application.port.out.ComplaintRepository
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.dispute.infrastructure.persistence.entity.ComplaintEntity
import com.openbank.dispute.infrastructure.persistence.entity.DisputeOutboxEntity
import com.openbank.dispute.infrastructure.persistence.mapper.ComplaintMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class ComplaintRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ComplaintMapper,
) : ComplaintRepository {

    @WithTransaction
    override fun save(complaint: Complaint, outbox: OutboxMessage): Uni<Complaint> {
        val entity = mapper.toEntity(complaint)
        return sf.withTransaction { s ->
            s.persist(entity)
                .flatMap { s.persist(outbox.toEntity()) }
                .map { mapper.toDomain(entity) }
        }
    }

    @WithSession
    override fun findById(id: UUID): Uni<Complaint?> =
        sf.withSession { s -> s.find(ComplaintEntity::class.java, id) }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findByStatus(status: ComplaintStatus): Uni<List<Complaint>> = sf.withSession { s ->
        s.createQuery("FROM ComplaintEntity WHERE status = :s ORDER BY createdAt DESC", ComplaintEntity::class.java)
            .setParameter("s", status).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findAll(): Uni<List<Complaint>> = sf.withSession { s ->
        s.createQuery("FROM ComplaintEntity ORDER BY createdAt DESC", ComplaintEntity::class.java).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun update(complaint: Complaint, outbox: OutboxMessage): Uni<Complaint> = sf.withTransaction { s ->
        s.find(ComplaintEntity::class.java, complaint.id).flatMap { e ->
            checkNotNull(e) { "Complaint vanished during update: ${complaint.id}" }
            applyUpdate(e, complaint)
            s.persist(e).flatMap { s.persist(outbox.toEntity()) }.map { mapper.toDomain(e) }
        }
    }
}

/** Copy the mutable lifecycle fields from a [Complaint] onto its persisted [ComplaintEntity]. */
private fun applyUpdate(e: ComplaintEntity, c: Complaint) {
    e.status = c.status
    e.dueDate = c.dueDate
    e.interimReplyAt = c.interimReplyAt
    e.interimReplyReason = c.interimReplyReason
    e.resolvedAt = c.resolvedAt
    e.outcome = c.outcome
    e.redressGranted = c.redressGranted
    e.rootCauseCode = c.rootCauseCode
    e.closedAt = c.closedAt
    e.updatedAt = c.updatedAt
}

/** Map a transactional-outbox message to its persisted entity (PENDING, shared dispute_outbox table). */
private fun OutboxMessage.toEntity() = DisputeOutboxEntity().also {
    it.eventId = eventId
    it.synthetic = synthetic
    it.aggregateId = aggregateId
    it.eventType = eventType
    it.payload = payload
    it.status = OutboxStatus.PENDING.name
    it.attemptCount = 0
    it.createdAt = createdAt
    it.updatedAt = createdAt
}
