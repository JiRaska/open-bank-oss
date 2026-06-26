// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.statement.application.port.out.StatementOutboxRepository
import com.openbank.statement.infrastructure.persistence.entity.StatementOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class StatementOutboxRepositoryImpl(private val clock: Clock) :
    StatementOutboxRepository,
    PanacheRepository<StatementOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> {
        val now = clock.instant()
        val e = StatementOutboxEntity().apply {
            eventId = message.eventId
            aggregateId = message.aggregateId
            eventType = message.eventType
            payload = message.payload
            status = OutboxStatus.PENDING.name
            attemptCount = 0
            createdAt = now
            updatedAt = now
        }
        return persist(e).replaceWithVoid()
    }

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
}
