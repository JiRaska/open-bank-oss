// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.domain.event.DisclosureSnapshotRequested
import com.openbank.delegation.domain.model.Disclosure
import com.openbank.delegation.infrastructure.persistence.entity.DisclosureEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DisclosureRepositoryImpl(
    private val outbox: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : DisclosureRepository {
    override suspend fun create(disclosure: Disclosure, event: DisclosureSnapshotRequested): Disclosure? =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_SQL)
                    .setParameter("id", disclosure.id)
                    .setParameter("requestId", disclosure.requestId)
                    .setParameter("delegationId", disclosure.delegationId)
                    .setParameter("grantorPartyId", disclosure.grantorPartyId)
                    .setParameter("sourceDocumentId", disclosure.sourceDocumentId)
                    .setParameter("createdAt", disclosure.createdAt)
                    .executeUpdate()
            }.flatMap { count ->
                if (count == 1) {
                    outbox.persistInTransaction(
                        OutboxMessage(
                            aggregateId = event.aggregateId,
                            eventType = event.eventType,
                            payload = objectMapper.writeValueAsString(event),
                            createdAt = event.occurredAt,
                        ),
                    ).replaceWith(disclosure)
                } else {
                    io.smallrye.mutiny.Uni.createFrom().nullItem<Disclosure>()
                }
            }
        }.awaitSuspending()

    override suspend fun findById(id: UUID): Disclosure? = Panache.withSession {
        Panache.getSession().flatMap { it.find(DisclosureEntity::class.java, id) }
    }.awaitSuspending()?.toDomain()

    override suspend fun findByRequestId(requestId: UUID): Disclosure? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery("FROM DisclosureEntity WHERE requestId = :requestId", DisclosureEntity::class.java)
                .setParameter("requestId", requestId).singleResultOrNull
        }
    }.awaitSuspending()?.toDomain()

    override suspend fun markReady(
        requestId: UUID,
        snapshotId: UUID,
        sourceDocumentId: UUID,
        sourceSha256: String,
        snapshotSha256: String,
        sizeBytes: Long,
        occurredAt: Instant,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(READY_SQL).setParameter("requestId", requestId)
                    .setParameter("snapshotId", snapshotId).setParameter("sourceDocumentId", sourceDocumentId)
                    .setParameter("sourceSha256", sourceSha256).setParameter("snapshotSha256", snapshotSha256)
                    .setParameter("sizeBytes", sizeBytes).setParameter("occurredAt", occurredAt).executeUpdate()
                    .replaceWithVoid()
            }
        }.awaitSuspending()
    }

    override suspend fun markRejected(requestId: UUID, reason: String, occurredAt: Instant) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(REJECTED_SQL).setParameter("requestId", requestId)
                    .setParameter("reason", reason).setParameter("occurredAt", occurredAt).executeUpdate()
                    .replaceWithVoid()
            }
        }.awaitSuspending()
    }

    private companion object {
        const val INSERT_SQL =
            "INSERT INTO delegation_disclosures(id, request_id, delegation_id, grantor_party_id, " +
                "source_document_id, status, created_at, updated_at) " +
                "SELECT :id, :requestId, g.id, g.grantor_party_id, g.resource_id, 'REQUESTED', " +
                ":createdAt, :createdAt FROM delegation_grants g WHERE g.id = :delegationId " +
                "AND g.grantor_party_id = :grantorPartyId AND g.resource_id = :sourceDocumentId " +
                "AND g.resource_type = 'DOCUMENT' AND g.status = 'ACTIVE' " +
                "ON CONFLICT (request_id) DO NOTHING"
        const val READY_SQL =
            "UPDATE delegation_disclosures SET status='READY', snapshot_id=:snapshotId, " +
                "source_sha256=:sourceSha256, snapshot_sha256=:snapshotSha256, size_bytes=:sizeBytes, " +
                "updated_at=:occurredAt WHERE request_id=:requestId " +
                "AND source_document_id=:sourceDocumentId AND status='REQUESTED'"
        const val REJECTED_SQL =
            "UPDATE delegation_disclosures SET status='REJECTED', rejection_reason=:reason, " +
                "updated_at=:occurredAt WHERE request_id=:requestId AND status='REQUESTED'"
    }
}
