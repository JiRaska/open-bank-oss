// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.application.port.out.SddOutboxRepository
import com.openbank.sdd.infrastructure.persistence.entity.SddOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Panache-backed outbox repository for the SDD service (ADR-0050).
 *
 * Implements both [SddOutboxRepository] (the dispatcher's read port) and [SddOutbox] (the
 * application layer's write port), keeping both backed by the same Panache active-record
 * session so they share a single CDI bean and a consistent transaction boundary.
 */
@ApplicationScoped
class SddOutboxRepositoryImpl :
    SddOutboxRepository,
    SddOutbox,
    PanacheRepository<SddOutboxEntity> {

    // --- SddOutbox (application-layer write port) ---

    override fun append(message: OutboxMessage): Uni<Void> {
        val e = message.toEntity()
        return persist(e).replaceWithVoid()
    }

    // --- SddOutboxRepository (dispatcher read port: OutboxRepository) ---

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()

    override suspend fun countProcessable(): Long = Panache.withSession {
        count(
            "status in (?1, ?2)",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        )
    }.awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = OutboxStatus.SENT.name
                    e.attemptCount += 1
                    e.sentAt = sentAt
                    e.lastError = null
                    e.updatedAt = sentAt
                }
            }.replaceWithVoid()
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.attemptCount += 1
                    e.status = OutboxFailurePolicy.statusAfterFailure(e.attemptCount).name
                    e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                    e.updatedAt = failedAt
                }
            }.replaceWithVoid()
        }.awaitSuspending()
    }

    private fun OutboxMessage.toEntity() = SddOutboxEntity().also {
        it.eventId = eventId
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = OutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }
}
