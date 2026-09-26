// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.security.application.port.out.IctIncidentOutboxRepository
import com.openbank.security.infrastructure.persistence.entity.IctIncidentOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class IctIncidentOutboxRepositoryImpl(private val clock: Clock) :
    IctIncidentOutboxRepository,
    PanacheRepository<IctIncidentOutboxEntity> {

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toEntry() }

    override suspend fun countProcessable(): Long = Panache.withSession {
        count("status in (?1, ?2)", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
    }.awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { entity ->
                if (entity != null) {
                    entity.status = OutboxStatus.SENT.name
                    entity.attemptCount += 1
                    entity.sentAt = sentAt
                    entity.lastError = null
                    entity.updatedAt = sentAt
                }
            }.replaceWithVoid()
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().map { entity ->
                if (entity == null) {
                    OutboxStatus.FAILED
                } else {
                    entity.attemptCount += 1
                    entity.status = OutboxFailurePolicy.statusAfterFailure(entity.attemptCount).name
                    entity.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                    entity.updatedAt = failedAt
                    OutboxStatus.valueOf(entity.status)
                }
            }
        }.awaitSuspending()

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, IctIncidentOutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("staleThreshold", now.minus(staleAfter))
                    .setParameter("claimLimit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .resultList
            }
        }.map { rows -> rows.map { it.toEntry() } }.awaitSuspending()
    }

    private companion object {
        @Suppress("MaxLineLength")
        const val CLAIM_SQL = """
            UPDATE ict_incident_outbox
            SET status = :dispatching, claimed_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM ict_incident_outbox
                WHERE status IN (:pending, :failed)
                   OR (status = :dispatching AND claimed_at < :staleThreshold)
                ORDER BY created_at ASC
                LIMIT :claimLimit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
        """
    }
}
