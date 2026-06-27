// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.transaction.application.port.out.TransactionOutboxRepository
import com.openbank.transaction.infrastructure.persistence.entity.TransactionOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class TransactionOutboxRepositoryImpl :
    TransactionOutboxRepository,
    PanacheRepository<TransactionOutboxEntity> {

    override suspend fun persistInTransaction(message: OutboxMessage) {
        persist(message.toEntity()).awaitSuspending()
    }

    fun persistMessageUni(message: OutboxMessage): Uni<Unit> = persist(message.toEntity()).replaceWith(Unit)

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toEntry() }

    override suspend fun countProcessable(): Long = Panache.withSession {
        count(
            "status in (?1, ?2)",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        )
    }.awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult()
                .invoke { entity ->
                    if (entity != null) {
                        entity.status = OutboxStatus.SENT.name
                        entity.attemptCount += 1
                        entity.sentAt = sentAt
                        entity.lastError = null
                        entity.updatedAt = sentAt
                    }
                }
                .replaceWith(Unit)
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult()
                .invoke { entity ->
                    if (entity != null) {
                        entity.attemptCount += 1
                        entity.status = OutboxFailurePolicy.statusAfterFailure(entity.attemptCount).name
                        entity.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                        entity.updatedAt = failedAt
                    }
                }
                .replaceWith(Unit)
        }.awaitSuspending()
    }

    private fun OutboxMessage.toEntity() = TransactionOutboxEntity().also {
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
