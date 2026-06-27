// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.persistence.repository

import com.openbank.security.infrastructure.persistence.entity.SecurityOutboxEntity
import com.openbank.security.application.port.out.SecurityOutboxEntry
import com.openbank.security.application.port.out.SecurityOutboxMessage
import com.openbank.security.application.port.out.SecurityOutboxRepository
import com.openbank.security.application.port.out.SecurityOutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SecurityOutboxRepositoryImpl : SecurityOutboxRepository, PanacheRepository<SecurityOutboxEntity> {

    fun persistInTransaction(message: SecurityOutboxMessage) =
        persist(message.toEntity())

    fun listProcessableUni(limit: Int): Uni<List<SecurityOutboxEntry>> =
        Panache.withSession {
            find(
                "status in (?1, ?2) order by createdAt asc",
                SecurityOutboxStatus.PENDING.name,
                SecurityOutboxStatus.FAILED.name
            ).range(0, limit.coerceAtLeast(1) - 1).list()
        }.map { entities -> entities.map { it.toEntry() } }

    /**
     * Backlog count via `SELECT count(*) WHERE status IN ('PENDING','FAILED')` — O(1) on the status
     * index, unlike materialising [listProcessableUni]. Runs on the event loop like the rest of the
     * reactive surface, so the scheduled gauge refresh never bridges a reactive session onto a
     * worker thread (ADR-0077 / ADR-0079).
     */
    fun countProcessableUni(): Uni<Long> =
        Panache.withSession {
            count(
                "status in (?1, ?2)",
                SecurityOutboxStatus.PENDING.name,
                SecurityOutboxStatus.FAILED.name
            )
        }

    fun markSentUni(eventId: UUID, sentAt: Instant = Instant.EPOCH): Uni<Void> =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = SecurityOutboxStatus.SENT.name
                    e.attemptCount += 1
                    e.sentAt = sentAt
                    e.lastError = null
                    e.updatedAt = sentAt
                }
            }.replaceWith(Unit)
        }.replaceWithVoid()

    fun markFailedUni(eventId: UUID, error: String, failedAt: Instant = Instant.EPOCH): Uni<Void> =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = SecurityOutboxStatus.FAILED.name
                    e.attemptCount += 1
                    e.lastError = error.take(4000)
                    e.updatedAt = failedAt
                }
            }.replaceWith(Unit)
        }.replaceWithVoid()

    override suspend fun listProcessable(limit: Int): List<SecurityOutboxEntry> =
        listProcessableUni(limit).awaitSuspending()

    override suspend fun countProcessable(): Long =
        countProcessableUni().awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        markSentUni(eventId, sentAt).awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        markFailedUni(eventId, error, failedAt).awaitSuspending()
    }

    private fun SecurityOutboxMessage.toEntity() = SecurityOutboxEntity().also {
        it.eventId = eventId
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = SecurityOutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }

    private fun SecurityOutboxEntity.toEntry() = SecurityOutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = eventType,
        payload = payload,
        status = SecurityOutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sentAt = sentAt,
        lastError = lastError
    )
}
