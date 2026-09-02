// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.infrastructure.persistence.repository

import com.openbank.cardissuance.application.port.out.CardOutboxRepository
import com.openbank.cardissuance.infrastructure.persistence.entity.CardOutboxEntity
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
class CardOutboxRepositoryImpl(private val clock: Clock) :
    CardOutboxRepository,
    PanacheRepository<CardOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()

    /**
     * Reference implementation for the [OutboxRepository.claimProcessable] atomic-claim
     * override (#1201). One statement: the inner `SELECT ... FOR UPDATE SKIP LOCKED` locks and
     * skips-past whatever a concurrently running claim has already locked, and the outer `UPDATE`
     * flips exactly those rows to DISPATCHING and returns them — so two dispatcher instances
     * racing this at the same instant can never both claim the same row. Also reclaims rows still
     * DISPATCHING past [staleAfter] (a pod that claimed a row and then crashed or was evicted
     * before `markSent`/`markFailed`), so a claim can never strand a row forever.
     *
     * Plain native SQL rather than a Panache/HQL lock hint: `FOR UPDATE SKIP LOCKED` has no
     * `jakarta.persistence.LockModeType` equivalent, and the lock only has to be held for the
     * lifetime of this one statement/transaction — it must not span the network publish call that
     * follows.
     */
    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        val staleThreshold = now.minus(staleAfter)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, CardOutboxEntity::class.java)
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

    override suspend fun countProcessable(): Long = Panache.withSession {
        count(
            "status in (?1, ?2)",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        )
    }.awaitSuspending()

    override suspend fun countDead(): Long = Panache.withSession {
        count("status = ?1", OutboxStatus.DEAD.name)
    }.awaitSuspending()

    /**
     * A **bulk HQL update**, not a read-modify-persist loop. Two reasons beyond efficiency: an
     * application-assigned `@Id` makes Panache reactive `persist()` INSERT-only, so a per-row
     * rewrite would need `session.merge`; and this runs against rows an operator has decided to
     * replay, where "requeued 24, silently wrote 0" is exactly the failure that must not be
     * possible — a bulk update returns the affected row count from the database itself.
     */
    override suspend fun requeueDead(eventId: UUID?): Int {
        val now = Instant.now(clock)
        return Panache.withTransaction {
            if (eventId == null) {
                update(
                    REQUEUE_HQL,
                    OutboxStatus.PENDING.name,
                    now,
                    OutboxStatus.DEAD.name,
                )
            } else {
                update(
                    "$REQUEUE_HQL and eventId = ?4",
                    OutboxStatus.PENDING.name,
                    now,
                    OutboxStatus.DEAD.name,
                    eventId,
                )
            }
        }.awaitSuspending()
    }

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
                            "card.outbox.dead event_id=%s aggregate_id=%s event_type=%s attempts=%d last_error=%s",
                            e.eventId,
                            e.aggregateId,
                            e.eventType,
                            e.attemptCount,
                            e.lastError,
                        )
                    }
                    OutboxStatus.valueOf(e.status)
                } else {
                    // Row not found -- unreachable in practice (the dispatcher only calls
                    // markFailed on a row it just claimed), but degrade gracefully rather than
                    // throw out of a batch that is otherwise mid-flight (#5128 finding 3).
                    OutboxStatus.FAILED
                }
            }
        }.awaitSuspending()

    private fun OutboxMessage.toEntity() = CardOutboxEntity().also {
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
        private val log: Logger = Logger.getLogger(CardOutboxRepositoryImpl::class.java)

        /**
         * `attemptCount = 0` and `lastError = null` are the load-bearing half. Leaving the counter
         * at its ceiling would hand the requeued row straight back to
         * `OutboxFailurePolicy.statusAfterFailure`, which re-parks it as DEAD on the very first
         * failed publish — a requeue that undoes itself and reads as "the requeue didn't work".
         * `createdAt` is untouched on purpose (see `CardOutboxRepository.requeueDead`).
         */
        private const val REQUEUE_HQL =
            "status = ?1, attemptCount = 0, lastError = null, claimedAt = null, updatedAt = ?2 where status = ?3"

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
