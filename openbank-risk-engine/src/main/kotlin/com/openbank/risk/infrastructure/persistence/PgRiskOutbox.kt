// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.risk.application.port.`in`.LimitAnalysis
import com.openbank.risk.application.port.out.LimitEventOutbox
import com.openbank.risk.domain.limits.RiskLimitEvents
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * `risk_outbox` on the plain reactive SQL client (the service ships no Hibernate): the write side
 * for limit events ([LimitEventOutbox]) and the dispatcher's [OutboxRepository].
 *
 * **Atomic.** Every row of one evaluation is inserted in ONE transaction, so a run's early-warning
 * and breach events commit together or not at all. **Idempotent.** Each insert is
 * `ON CONFLICT (dedup_key) DO NOTHING` on (run, limit id, limit-set id, limit-set version), so a
 * replayed run or two pods ticking together write each event once. **Claimed.** The dispatcher claims
 * rows with `FOR UPDATE SKIP LOCKED` and reclaims a claim older than `staleAfter` (#1201), because an
 * Argo Rollouts canary runs two dispatchers at once.
 */
@ApplicationScoped
class PgRiskOutbox(private val pool: Pool, private val mapper: ObjectMapper) :
    OutboxRepository,
    LimitEventOutbox {

    override suspend fun recordNonOk(analysis: LimitAnalysis, occurredAt: Instant): Int {
        val run = analysis.run
        val events = RiskLimitEvents.of(run, analysis.set, analysis.evaluations, occurredAt)
        if (events.isEmpty()) return 0
        val rows = events.map { e ->
            val message = OutboxMessage(
                aggregateId = run.id,
                eventType = e.eventType,
                payload = mapper.writeValueAsString(e.payload),
                createdAt = occurredAt,
            )
            Tuple.tuple(
                listOf(
                    message.eventId,
                    message.aggregateId,
                    message.eventType,
                    message.payload,
                    "${run.id}:${e.limitId}:${analysis.set.id}:${analysis.set.version}",
                    message.createdAt.atOffset(ZoneOffset.UTC),
                    message.synthetic,
                ),
            )
        }
        return pool.withTransaction { conn ->
            conn.preparedQuery(INSERT).executeBatch(rows).map { result ->
                // executeBatch chains one RowSet per tuple; each counts 0 (conflict) or 1 (inserted).
                generateSequence(result) { it.next() }.sumOf { it.rowCount() }
            }
        }.awaitSuspending()
    }

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> =
        pool.preparedQuery(SELECT_PROCESSABLE).execute(Tuple.of(limit)).awaitSuspending().map(::entry)

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> =
        pool.preparedQuery(CLAIM).execute(Tuple.of(limit, staleAfter.seconds.toDouble()))
            .awaitSuspending().map(::entry).sortedBy { it.createdAt }

    override suspend fun countProcessable(): Long =
        pool.preparedQuery(COUNT_PROCESSABLE).execute().awaitSuspending().first().getLong(0)

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        pool.preparedQuery(MARK_SENT).execute(Tuple.of(eventId, sentAt.atOffset(ZoneOffset.UTC))).awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus {
        val row = pool.preparedQuery(MARK_FAILED).execute(
            Tuple.of(
                eventId,
                error.take(OutboxFailurePolicy.MAX_ERROR_LEN),
                failedAt.atOffset(ZoneOffset.UTC),
                OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS,
            ),
        ).awaitSuspending().firstOrNull() ?: return OutboxStatus.FAILED
        return OutboxStatus.valueOf(row.getString("status"))
    }

    private fun entry(row: Row) = OutboxEntry(
        eventId = row.getUUID("event_id"),
        aggregateId = row.getUUID("aggregate_id"),
        eventType = row.getString("event_type"),
        payload = row.getString("payload"),
        status = OutboxStatus.valueOf(row.getString("status")),
        attemptCount = row.getInteger("attempt_count"),
        createdAt = row.getOffsetDateTime("created_at").toInstant(),
        updatedAt = row.getOffsetDateTime("updated_at").toInstant(),
        sentAt = row.getOffsetDateTime("sent_at")?.toInstant(),
        lastError = row.getString("last_error"),
        synthetic = row.getBoolean("synthetic"),
    )

    private companion object {
        const val COLUMNS =
            "event_id, aggregate_id, event_type, payload, status, attempt_count, created_at, updated_at, " +
                "sent_at, last_error, synthetic"
        const val INSERT =
            "INSERT INTO risk_outbox (event_id, aggregate_id, event_type, payload, dedup_key, created_at, " +
                "updated_at, synthetic) VALUES ($1, $2, $3, $4, $5, $6, $6, $7) ON CONFLICT (dedup_key) DO NOTHING"
        const val SELECT_PROCESSABLE =
            "SELECT $COLUMNS FROM risk_outbox WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at LIMIT $1"
        const val COUNT_PROCESSABLE = "SELECT count(*) FROM risk_outbox WHERE status IN ('PENDING', 'FAILED')"
        const val CLAIM =
            "UPDATE risk_outbox SET status = 'DISPATCHING', claimed_at = now(), updated_at = now() " +
                "WHERE event_id IN (SELECT event_id FROM risk_outbox WHERE status IN ('PENDING', 'FAILED') " +
                "OR (status = 'DISPATCHING' AND claimed_at < now() - make_interval(secs => $2)) " +
                "ORDER BY created_at LIMIT $1 FOR UPDATE SKIP LOCKED) RETURNING $COLUMNS"
        const val MARK_SENT =
            "UPDATE risk_outbox SET status = 'SENT', sent_at = $2, updated_at = $2, last_error = NULL WHERE event_id = $1"
        const val MARK_FAILED =
            "UPDATE risk_outbox SET attempt_count = attempt_count + 1, last_error = $2, updated_at = $3, " +
                "status = CASE WHEN attempt_count + 1 >= $4 THEN 'DEAD' ELSE 'FAILED' END " +
                "WHERE event_id = $1 RETURNING status"
    }
}
