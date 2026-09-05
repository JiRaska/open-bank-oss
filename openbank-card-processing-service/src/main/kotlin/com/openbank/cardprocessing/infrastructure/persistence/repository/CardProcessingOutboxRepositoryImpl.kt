// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.CardProcessingOutboxRepository
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardProcessingOutboxEntity
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class CardProcessingOutboxRepositoryImpl(private val clock: Clock) :
    CardProcessingOutboxRepository,
    PanacheRepository<CardProcessingOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()

    /**
     * Atomic cross-pod claim (#1201). `concurrentExecution = SKIP` stops in-JVM overlap only; an
     * Argo Rollouts canary window runs the old and the new pod at once, each on its own tick, so
     * without `FOR UPDATE SKIP LOCKED` both would claim and publish the same row. Also reclaims a
     * row left DISPATCHING by a pod that died mid-publish, so a claim cannot strand a row forever.
     */
    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, CardProcessingOutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("staleThreshold", now.minus(staleAfter))
                    .setParameter("claimLimit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .resultList
            }
        }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()
    }

    override suspend fun countProcessable(): Long = Panache.withSession {
        count("status in (?1, ?2)", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
    }.awaitSuspending()

    override suspend fun countDead(): Long = Panache.withSession {
        count("status = ?1", OutboxStatus.DEAD.name)
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

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().map { e ->
                if (e != null) {
                    e.attemptCount += 1
                    e.status = OutboxFailurePolicy.statusAfterFailure(e.attemptCount).name
                    e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                    e.updatedAt = failedAt
                    if (e.status == OutboxStatus.DEAD.name) {
                        log.warnf(
                            "card-processing.outbox.dead event_id=%s aggregate_id=%s type=%s attempts=%d error=%s",
                            e.eventId,
                            e.aggregateId,
                            e.eventType,
                            e.attemptCount,
                            e.lastError,
                        )
                    }
                    OutboxStatus.valueOf(e.status)
                } else {
                    // The dispatcher only calls this on a row it just claimed, so this is
                    // unreachable in practice — but degrading beats throwing out of a live batch.
                    OutboxStatus.FAILED
                }
            }
        }.awaitSuspending()

    private fun OutboxMessage.toEntity() = CardProcessingOutboxEntity().also {
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

    companion object {
        private val log: Logger = Logger.getLogger(CardProcessingOutboxRepositoryImpl::class.java)

        @Suppress("MaxLineLength")
        private const val CLAIM_SQL = """
            UPDATE card_outbox
            SET status = :dispatching, claimed_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM card_outbox
                WHERE (status IN (:pending, :failed))
                   OR (status = :dispatching AND claimed_at < :staleThreshold)
                ORDER BY created_at ASC
                LIMIT :claimLimit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
        """
    }
}
