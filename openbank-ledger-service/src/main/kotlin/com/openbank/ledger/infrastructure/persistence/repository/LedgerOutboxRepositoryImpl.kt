// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.LedgerOutboxRepository
import com.openbank.ledger.infrastructure.persistence.entity.LedgerOutboxEntity
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
class LedgerOutboxRepositoryImpl(private val clock: Clock) :
    LedgerOutboxRepository,
    PanacheRepository<LedgerOutboxEntity> {

    fun persistInTransaction(message: OutboxMessage) = persist(message.toEntity())

    // --- Reactive (Mutiny) surface used by the event-loop dispatcher (ADR-0050 N1) ---
    // The dispatch chain runs entirely on the Vert.x event loop, so it never bridges a
    // reactive session onto a worker thread (the HR000068 class of bug). PENDING and FAILED
    // rows are eligible; DEAD rows (N5) and SENT rows are excluded by the status filter.

    fun listProcessableUni(limit: Int): Uni<List<OutboxEntry>> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { entities -> entities.map { it.toEntry() } }

    /**
     * Backlog count via `SELECT count(*) WHERE status IN ('PENDING','FAILED')` — O(1) on the
     * status index, unlike materialising [listProcessableUni]. Runs on the event loop like the
     * rest of the reactive surface (ADR-0050 N1), so the scheduled gauge refresh never bridges a
     * reactive session onto a worker thread.
     */
    fun countProcessableUni(): Uni<Long> = Panache.withSession {
        count(
            "status in (?1, ?2)",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        )
    }

    // --- Suspend surface (OutboxRepository port via LedgerOutboxRepository) ---

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = listProcessableUni(limit).awaitSuspending()

    override suspend fun countProcessable(): Long = countProcessableUni().awaitSuspending()

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
                session.createNativeQuery(CLAIM_SQL, LedgerOutboxEntity::class.java)
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
            }.replaceWith(Unit)
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().map { e ->
                if (e != null) {
                    applyFailure(e, error, failedAt)
                } else {
                    // Row not found -- unreachable in practice (the dispatcher only calls
                    // markFailed on a row it just claimed), but degrade gracefully rather than
                    // throw out of a batch that is otherwise mid-flight (#5128 finding 3).
                    OutboxStatus.FAILED
                }
            }
        }.awaitSuspending()

    /**
     * Record a publish failure (ADR-0050 N5). Increments the attempt counter and, once the
     * configured cap is reached, transitions the row to the terminal DEAD state and emits a
     * WARN an operator alert can hook — so a poison row can neither be retried forever nor
     * starve the batch.
     */
    private fun applyFailure(e: LedgerOutboxEntity, error: String, at: Instant = Instant.now(clock)): OutboxStatus {
        e.attemptCount += 1
        e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
        e.updatedAt = at
        val next = OutboxFailurePolicy.statusAfterFailure(e.attemptCount)
        e.status = next.name
        if (next == OutboxStatus.DEAD) {
            log.warnf(
                "ledger.outbox.dead event_id=%s aggregate_id=%s event_type=%s attempts=%d last_error=%s",
                e.eventId,
                e.aggregateId,
                e.eventType,
                e.attemptCount,
                e.lastError,
            )
        }
        return next
    }

    private fun OutboxMessage.toEntity() = LedgerOutboxEntity().also {
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
        private val log: Logger = Logger.getLogger(LedgerOutboxRepositoryImpl::class.java)

        // Kept for test backwards compatibility — delegates to the canonical libs policy.
        const val MAX_ATTEMPTS: Int = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS

        fun statusAfterFailure(attemptCount: Int): OutboxStatus = OutboxFailurePolicy.statusAfterFailure(attemptCount)

        @Suppress("MaxLineLength")
        private const val CLAIM_SQL = """
            UPDATE ledger_outbox
            SET status = :dispatching, claimed_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM ledger_outbox
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
