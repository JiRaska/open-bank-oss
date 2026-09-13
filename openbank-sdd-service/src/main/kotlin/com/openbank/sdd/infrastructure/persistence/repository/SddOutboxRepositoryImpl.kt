// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.application.port.out.SddOutboxRepository
import com.openbank.sdd.infrastructure.persistence.entity.SddOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
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
class SddOutboxRepositoryImpl(private val clock: Clock) :
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

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().map { e ->
                if (e != null) {
                    e.attemptCount += 1
                    e.status = OutboxFailurePolicy.statusAfterFailure(e.attemptCount).name
                    e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                    e.updatedAt = failedAt
                    OutboxStatus.valueOf(e.status)
                } else {
                    // Row not found -- unreachable in practice (the dispatcher only calls
                    // markFailed on a row it just claimed), but degrade gracefully rather than
                    // throw out of a batch that is otherwise mid-flight (#5128 finding 3).
                    OutboxStatus.FAILED
                }
            }
        }.awaitSuspending()

    /**
     * Reference implementation for the [OutboxRepository.claimProcessable] atomic-claim
     * override (#1201). One statement: the inner `SELECT ... FOR UPDATE SKIP LOCKED` locks and
     * skips-past whatever a concurrently running claim has already locked, and the outer
     * `UPDATE` flips exactly those rows to DISPATCHING and returns them — so two dispatcher
     * instances racing this at the same instant can never both claim the same row. Also reclaims
     * rows still DISPATCHING past [staleAfter] (a pod that claimed a row and then crashed or was
     * evicted before `markSent`/`markFailed`), so a claim can never strand a row forever.
     *
     * Plain native SQL rather than a Panache/HQL lock hint: `FOR UPDATE SKIP LOCKED` has no
     * `jakarta.persistence.LockModeType` equivalent, and the lock only has to be held for the
     * lifetime of this one statement/transaction — it does not need to (and must not) span the
     * network publish call that follows.
     */
    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        val staleThreshold = now.minus(staleAfter)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, SddOutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("staleThreshold", staleThreshold)
                    .setParameter("claimLimit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .resultList
            }
        }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()
    }

    private fun OutboxMessage.toEntity() = SddOutboxEntity().also {
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
        @Suppress("MaxLineLength")
        private const val CLAIM_SQL = """
            UPDATE sdd_outbox
            SET status = :dispatching, claimed_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM sdd_outbox
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
