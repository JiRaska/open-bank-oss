// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.AmlOutboxRepository
import com.openbank.aml.infrastructure.persistence.entity.AmlOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AmlOutboxRepositoryImpl(private val clock: Clock) :
    AmlOutboxRepository,
    PanacheRepository<AmlOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = listProcessableUni(limit).awaitSuspending()

    fun listProcessableUni(limit: Int): Uni<List<OutboxEntry>> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.onItem().transform { list -> list.map { it.toEntry() } }

    override suspend fun countProcessable(): Long = countProcessableUni().awaitSuspending()

    fun countProcessableUni(): Uni<Long> = Panache.withSession {
        count(
            "status in (?1, ?2)",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        )
    }

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        markSentUni(eventId, sentAt).awaitSuspending()
    }

    fun markSentUni(eventId: UUID, sentAt: Instant = Instant.now(clock)): Uni<Unit> = Panache.withTransaction {
        find("eventId", eventId).firstResult().invoke { e ->
            if (e != null) {
                e.status = OutboxStatus.SENT.name
                e.attemptCount += 1
                e.sentAt = sentAt
                e.lastError = null
                e.updatedAt = sentAt
            }
        }.replaceWith(Unit)
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        markFailedUni(eventId, error, failedAt).awaitSuspending()
    }

    fun markFailedUni(eventId: UUID, error: String, failedAt: Instant = Instant.now(clock)): Uni<Unit> =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    applyFailure(e, error, failedAt)
                }
            }.replaceWith(Unit)
        }

    /**
     * Record a publish failure (ADR-0050 N5). Increments the attempt counter and, once the
     * configured cap is reached, parks the row in the terminal DEAD state and emits a WARN an
     * operator alert can hook — so a poison row can neither be retried forever nor starve the batch.
     */
    private fun applyFailure(e: AmlOutboxEntity, error: String, at: Instant = Instant.now(clock)) {
        e.attemptCount += 1
        e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
        e.updatedAt = at
        val next = OutboxFailurePolicy.statusAfterFailure(e.attemptCount)
        e.status = next.name
        if (next == OutboxStatus.DEAD) {
            log.warnf(
                "aml.outbox.dead event_id=%s aggregate_id=%s event_type=%s attempts=%d last_error=%s",
                e.eventId,
                e.aggregateId,
                e.eventType,
                e.attemptCount,
                e.lastError,
            )
        }
    }

    private fun OutboxMessage.toEntity() = AmlOutboxEntity().also {
        it.eventId = eventId
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = OutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }

    companion object {
        private val log: Logger = Logger.getLogger(AmlOutboxRepositoryImpl::class.java)
    }
}
