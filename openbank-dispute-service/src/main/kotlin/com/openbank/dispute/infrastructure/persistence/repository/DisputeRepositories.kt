// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.persistence.repository

import com.openbank.dispute.application.port.out.*
import com.openbank.dispute.domain.model.*
import com.openbank.dispute.infrastructure.persistence.entity.*
import com.openbank.dispute.infrastructure.persistence.mapper.DisputeMapper
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
class DisputeRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: DisputeMapper,
) : DisputeRepository {
    @WithTransaction override fun save(dispute: Dispute): Uni<Dispute> {
        val e = mapper.toEntity(dispute)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    // Mirrors update(dispute, outbox): one transaction, so a dispute cannot exist without the
    // event that announced it, nor an event without the dispute.
    override fun save(dispute: Dispute, outbox: List<OutboxMessage>): Uni<Dispute> = sf.withTransaction { s ->
        val e = mapper.toEntity(dispute)
        var chain = s.persist(e)
        for (message in outbox) {
            chain = chain.flatMap { s.persist(message.toOutboxEntity()) }
        }
        chain.map { mapper.toDomain(e) }
    }

    @WithSession override fun findById(id: UUID): Uni<Dispute?> =
        sf.withSession { s -> s.find(DisputeEntity::class.java, id) }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByReference(reference: String): Uni<Dispute?> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEntity WHERE reference = :r",
            DisputeEntity::class.java,
        ).setParameter("r", reference).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByAccountId(accountId: UUID): Uni<List<Dispute>> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEntity WHERE accountId = :a ORDER BY createdAt DESC",
            DisputeEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findByStatus(status: DisputeStatus): Uni<List<Dispute>> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEntity WHERE status = :s ORDER BY createdAt DESC",
            DisputeEntity::class.java,
        ).setParameter("s", status).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun update(dispute: Dispute): Uni<Dispute> = sf.withTransaction { s ->
        s.find(DisputeEntity::class.java, dispute.id).flatMap { e ->
            applyUpdate(e!!, dispute)
            s.persist(e).map { mapper.toDomain(e) }
        }
    }

    @WithTransaction
    override fun update(dispute: Dispute, outbox: List<OutboxMessage>): Uni<Dispute> = sf.withTransaction { s ->
        s.find(DisputeEntity::class.java, dispute.id).flatMap { e ->
            applyUpdate(e!!, dispute)
            var chain = s.persist(e)
            for (message in outbox) {
                chain = chain.flatMap { s.persist(message.toOutboxEntity()) }
            }
            chain.map { mapper.toDomain(e) }
        }
    }

    private fun applyUpdate(e: DisputeEntity, dispute: Dispute) {
        e.status = dispute.status
        e.resolution = dispute.resolution
        e.chargebackAmount = dispute.chargebackAmount
        e.resolvedAt = dispute.resolvedAt
        e.resolvedBy = dispute.resolvedBy
        e.remediationOutcome = dispute.remediationOutcome
        e.remediationAmount = dispute.remediationAmount
        e.updatedAt = dispute.updatedAt
    }

    private fun OutboxMessage.toOutboxEntity() = DisputeOutboxEntity().also {
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
}

@ApplicationScoped
class DisputeEvidenceRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: DisputeMapper,
) : DisputeEvidenceRepository {
    @WithTransaction override fun save(evidence: DisputeEvidence): Uni<DisputeEvidence> {
        val e = mapper.toEntity(evidence)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findByDisputeId(disputeId: UUID): Uni<List<DisputeEvidence>> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEvidenceEntity WHERE disputeId = :d ORDER BY submittedAt DESC",
            DisputeEvidenceEntity::class.java,
        ).setParameter("d", disputeId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findByDisputeIdOrderedBySequence(disputeId: UUID): Uni<List<DisputeEvidence>> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEvidenceEntity WHERE disputeId = :d ORDER BY sequence ASC",
            DisputeEvidenceEntity::class.java,
        ).setParameter("d", disputeId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findLatestByDisputeId(disputeId: UUID): Uni<DisputeEvidence?> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeEvidenceEntity WHERE disputeId = :d ORDER BY sequence DESC",
            DisputeEvidenceEntity::class.java,
        ).setParameter("d", disputeId).setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }
}

@ApplicationScoped
class DisputeTimelineRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: DisputeMapper,
) : DisputeTimelineRepository {
    @WithTransaction override fun save(event: DisputeTimelineEvent): Uni<DisputeTimelineEvent> {
        val e = mapper.toEntity(event)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findByDisputeId(disputeId: UUID): Uni<List<DisputeTimelineEvent>> = sf.withSession { s ->
        s.createQuery(
            "FROM DisputeTimelineEntity WHERE disputeId = :d ORDER BY createdAt ASC",
            DisputeTimelineEntity::class.java,
        ).setParameter("d", disputeId).resultList
    }.map { it.map(mapper::toDomain) }
}
