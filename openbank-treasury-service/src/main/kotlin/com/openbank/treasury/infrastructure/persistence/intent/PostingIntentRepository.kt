// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.intent

import com.openbank.treasury.application.port.out.OpenIntentSummary
import com.openbank.treasury.application.port.out.PostingIntent
import com.openbank.treasury.application.port.out.PostingIntentAction
import com.openbank.treasury.application.port.out.PostingIntentCloseOutcome
import com.openbank.treasury.application.port.out.PostingIntentPort
import com.openbank.treasury.application.port.out.PostingIntentStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.PostingEvent
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `deal_posting_intent` (V27, #13883). Completion is not here: [com.openbank.treasury.infrastructure
 * .persistence.repository.DealRepositoryImpl.save] completes the intent in the transaction that
 * stores the state change and the journal.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class PostingIntentRepository(private val sf: Mutiny.SessionFactory, private val clock: Clock) : PostingIntentPort {

    /**
     * Insert, else reopen a REFUSED row as this caller's attempt (new value day, actor, reason, command
     * key and creation time: a refused post left no journal), else keep the OPEN/COMPLETED row as it
     * stands. Both writes are single atomic statements, so two racing callers cannot both be fresh.
     */
    override suspend fun open(intent: PostingIntent): PostingIntent? = try {
        sf.withTransaction { s ->
            write(s, INSERT_SQL, intent)
                .flatMap { inserted ->
                    if (inserted == 1) {
                        Uni.createFrom().item(true)
                    } else {
                        write(s, REOPEN_SQL, intent).flatMap { reopened ->
                            if (reopened == 1) {
                                Uni.createFrom().item(true)
                            } else {
                                // After an operator close a journal may exist: reopen, NOT fresh, first day kept.
                                native(
                                    s,
                                    REOPEN_CLOSED_SQL,
                                    intent.idempotencyKey,
                                    utc(intent.createdAt),
                                    intent.actor.id,
                                    intent.actor.type.name,
                                ).map { false }
                            }
                        }
                    }
                }
                .flatMap { fresh ->
                    s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE idempotency_key = ?1")
                        .setParameter(1, intent.idempotencyKey)
                        .resultList
                        .map { rows -> rows.firstOrNull()?.toIntent()?.copy(fresh = fresh) }
                }
        }.awaitSuspending()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception, // classified below; anything else rethrown
    ) {
        if (isOnePerDealViolation(e)) null else throw e
    }

    /**
     * The one-open-per-deal index refused a reopen (the insert's DO NOTHING covers its own). The
     * reactive stack wraps the driver's error in whatever it likes (CompletionException, Mutiny's
     * CompositeException, Hibernate's PersistenceException), so every cause, suppressed and
     * composite member is walked; the match is SQLState 23505 on this index, or its name.
     */
    private fun isOnePerDealViolation(e: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        val queue = ArrayDeque(listOf(e))
        while (queue.isNotEmpty() && seen.size < MAX_CAUSES) {
            val t = queue.removeFirst()
            if (!seen.add(t)) continue
            if (t is PgException && t.sqlState == UNIQUE_VIOLATION && t.constraint == ONE_PER_DEAL_INDEX) return true
            if (t.message?.contains(ONE_PER_DEAL_INDEX) == true) return true
            t.cause?.let(queue::add)
            queue.addAll(t.suppressed)
            if (t is io.smallrye.mutiny.CompositeException) queue.addAll(t.causes)
        }
        return false
    }

    private fun write(s: Mutiny.Session, sql: String, intent: PostingIntent): Uni<Int> = s.createNativeQuery<Int>(sql)
        .setParameter(P_KEY, intent.idempotencyKey)
        .setParameter(P_DEAL, intent.dealId)
        .setParameter(P_EVENT, intent.event.name)
        .setParameter(P_ACTION, intent.action.name)
        .setParameter(P_VALUE_DATE, intent.valueDate)
        .setParameter(P_ACTOR_ID, intent.actor.id)
        .setParameter(P_ACTOR_TYPE, intent.actor.type.name)
        .setParameter(P_REASON, intent.reason)
        .setParameter(P_COMMAND, intent.commandKey)
        .setParameter(P_CREATED, utc(intent.createdAt))
        .executeUpdate()

    override suspend fun refuse(key: String, reason: String) {
        update(REFUSE_SQL, utc(clock.instant()), reason, key)
    }

    override suspend fun failed(key: String, error: String) {
        update(
            "UPDATE deal_posting_intent SET attempts = attempts + 1, last_error = ?1, " +
                "claim_token = NULL, claimed_until = NULL WHERE idempotency_key = ?2 AND status = 'OPEN'",
            error,
            key,
        )
    }

    override suspend fun escalate(key: String, reason: String) {
        update(
            "UPDATE deal_posting_intent SET escalated_at = ?1, last_error = ?2, claim_token = NULL, " +
                "claimed_until = NULL WHERE idempotency_key = ?3 AND status = 'OPEN'",
            utc(clock.instant()),
            reason,
            key,
        )
    }

    override suspend fun closeByOperator(
        key: String,
        actor: Actor,
        reason: String,
        createdBefore: Instant,
    ): PostingIntentCloseOutcome {
        if (update(CLOSE_SQL, utc(clock.instant()), actor.id, reason, key, utc(createdBefore)) == 1) {
            return PostingIntentCloseOutcome.CLOSED
        }
        val stillOpen = sf.withSession { s ->
            s.createNativeQuery<Any>("SELECT 1 FROM deal_posting_intent WHERE idempotency_key = ?1 AND status = 'OPEN'")
                .setParameter(1, key)
                .resultList
        }.awaitSuspending().isNotEmpty()
        return if (stillOpen) PostingIntentCloseOutcome.IN_FLIGHT else PostingIntentCloseOutcome.NOT_OPEN
    }

    private suspend fun update(sql: String, vararg params: Any?): Int =
        sf.withTransaction { s -> native(s, sql, *params) }.awaitSuspending()

    private fun native(s: Mutiny.Session, sql: String, vararg params: Any?): Uni<Int> =
        params.foldIndexed(s.createNativeQuery<Int>(sql)) { i, q, v -> q.setParameter(i + 1, v) }.executeUpdate()

    /**
     * One statement: the inner SELECT locks the candidate rows `FOR UPDATE SKIP LOCKED` (a row another
     * pass is claiming right now is skipped, not waited for), and the UPDATE stamps them with this
     * pass's token and lease. A lease that lapsed (a pod died mid-pass) makes the row claimable again.
     */
    override suspend fun claimOpen(cutoff: Instant, lease: Duration, limit: Int): List<PostingIntent> {
        val token = UUID.randomUUID()
        val now = clock.instant()
        return sf.withTransaction { s ->
            listOf(token, utc(now.plus(lease)), utc(cutoff), utc(now), limit)
                .foldIndexed(s.createNativeQuery<Int>(CLAIM_SQL)) { i, q, v -> q.setParameter(i + 1, v) }
                .executeUpdate()
                .flatMap {
                    s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE claim_token = ?1 ORDER BY created_at")
                        .setParameter(1, token)
                        .resultList
                }
        }.awaitSuspending().map { it.toIntent() }
    }

    override suspend fun openFor(dealId: UUID): List<PostingIntent> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE deal_id = ?1 AND status = 'OPEN' ORDER BY created_at")
            .setParameter(1, dealId)
            .resultList
    }.awaitSuspending().map { it.toIntent() }

    override suspend fun unresolvedFor(dealId: UUID): List<PostingIntent> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(
            "$SELECT_SQL WHERE deal_id = ?1 AND status IN ('OPEN', 'CLOSED_BY_OPERATOR') ORDER BY created_at",
        ).setParameter(1, dealId).resultList
    }.awaitSuspending().map { it.toIntent() }

    override suspend fun blocksAccrual(dealId: UUID): Boolean = sf.withSession { s ->
        s.createNativeQuery<Any>(
            "SELECT 1 FROM deal_posting_intent WHERE deal_id = ?1 AND (status = 'OPEN' OR " +
                "(status = 'CLOSED_BY_OPERATOR' AND action IN ('REVERSE', 'MATURE'))) LIMIT 1",
        ).setParameter(1, dealId).resultList
    }.awaitSuspending().isNotEmpty()

    override suspend fun summary(): OpenIntentSummary = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(
            "SELECT count(*), min(created_at) FROM deal_posting_intent WHERE status = 'OPEN'",
        ).singleResult
    }.awaitSuspending().let { OpenIntentSummary((it[0] as Number).toInt(), it[1]?.let(::asInstant)) }

    private fun utc(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)

    // Column order of SELECT_SQL.
    @Suppress("MagicNumber")
    private fun Array<Any?>.toIntent() = PostingIntent(
        idempotencyKey = this[0] as String,
        dealId = this[1] as? UUID ?: UUID.fromString(this[1].toString()),
        event = PostingEvent.valueOf(this[2] as String),
        action = PostingIntentAction.valueOf(this[3] as String),
        valueDate = asDate(this[4]),
        actor = Actor(this[5] as String, ActorType.valueOf(this[6] as String)),
        reason = this[7] as? String,
        commandKey = this[8] as? String,
        createdAt = asInstant(this[9]),
        attempts = (this[10] as Number).toInt(),
        lastError = this[11] as? String,
        status = PostingIntentStatus.valueOf(this[12] as String),
    )

    private fun asDate(value: Any?): LocalDate = when (value) {
        is LocalDate -> value
        is java.sql.Date -> value.toLocalDate()
        else -> LocalDate.parse(value.toString())
    }

    private fun asInstant(value: Any?): Instant = when (value) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> OffsetDateTime.parse(value.toString()).toInstant()
    }

    private companion object {
        const val P_KEY = 1
        const val P_DEAL = 2
        const val P_EVENT = 3
        const val P_ACTION = 4
        const val P_VALUE_DATE = 5
        const val P_ACTOR_ID = 6
        const val P_ACTOR_TYPE = 7
        const val P_REASON = 8
        const val P_COMMAND = 9
        const val P_CREATED = 10

        // Every transition writes its history row in the SAME statement (a data-modifying CTE): the
        // statement's count is the history rows written, which is the transitions that happened.
        const val ONE_PER_DEAL_INDEX = "uq_deal_posting_intent_one_open_per_deal"
        const val MAX_CAUSES = 32
        const val UNIQUE_VIOLATION = "23505"

        // ON CONFLICT with no target: a clash on the key (a retry) AND a clash on the one-open-per-deal
        // index (another settlement, reversal, maturity or accrual day in flight) both insert nothing. The SELECT
        // after it tells them apart: the key's own row, or no row at all (blocked).
        const val INSERT_SQL =
            "WITH i AS (INSERT INTO deal_posting_intent (idempotency_key, deal_id, event, action, value_date, " +
                "actor_id, actor_type, reason, command_key, created_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) ON CONFLICT DO NOTHING " +
                "RETURNING idempotency_key, value_date) " +
                "INSERT INTO deal_posting_intent_history (idempotency_key, at, transition, actor_id, value_date) " +
                "SELECT idempotency_key, ?10, 'OPENED', ?6, value_date FROM i"

        // Only a REFUSED row (no journal exists) is reopened as a new attempt, taking its value day.
        // attempts, last_error and the operator-close columns are left as they are.
        const val REOPEN_SQL =
            "WITH u AS (UPDATE deal_posting_intent SET status = 'OPEN', closed_at = NULL, escalated_at = NULL, " +
                "deal_id = ?2, " +
                "event = ?3, action = ?4, value_date = ?5, actor_id = ?6, actor_type = ?7, reason = ?8, " +
                "command_key = ?9, created_at = ?10 WHERE idempotency_key = ?1 AND status = 'REFUSED' " +
                "RETURNING idempotency_key, value_date) " +
                "INSERT INTO deal_posting_intent_history (idempotency_key, at, transition, actor_id, value_date) " +
                "SELECT idempotency_key, ?10, 'REOPENED', ?6, value_date FROM u"

        // A re-issue after an operator close: OPEN again with its FIRST value day (a journal may exist),
        // closed_by/close_reason kept (the history holds every close). It is a new decision by the
        // re-issuer: their actor and time (so the grace and four eyes apply to them), not escalated.
        const val REOPEN_CLOSED_SQL =
            "WITH u AS (UPDATE deal_posting_intent SET status = 'OPEN', closed_at = NULL, created_at = ?2, " +
                "actor_id = ?3, actor_type = ?4, escalated_at = NULL, claim_token = NULL, claimed_until = NULL " +
                "WHERE idempotency_key = ?1 AND status = 'CLOSED_BY_OPERATOR' RETURNING idempotency_key, value_date) " +
                "INSERT INTO deal_posting_intent_history (idempotency_key, at, transition, actor_id, value_date) " +
                "SELECT idempotency_key, ?2, 'REOPENED_AFTER_OPERATOR_CLOSE', ?3, value_date FROM u"

        const val REFUSE_SQL =
            "WITH u AS (UPDATE deal_posting_intent SET status = 'REFUSED', closed_at = ?1, last_error = ?2, " +
                "claim_token = NULL, claimed_until = NULL WHERE idempotency_key = ?3 AND status = 'OPEN' " +
                "RETURNING idempotency_key, value_date) " +
                "INSERT INTO deal_posting_intent_history (idempotency_key, at, transition, reason, value_date) " +
                "SELECT idempotency_key, ?1, 'REFUSED', ?2, value_date FROM u"

        const val CLOSE_SQL =
            "WITH u AS (UPDATE deal_posting_intent SET status = 'CLOSED_BY_OPERATOR', closed_at = ?1, " +
                "closed_by = ?2, close_reason = ?3, claim_token = NULL, claimed_until = NULL " +
                "WHERE idempotency_key = ?4 AND status = 'OPEN' AND created_at < ?5 " +
                "AND (claimed_until IS NULL OR claimed_until < ?1) RETURNING idempotency_key, value_date) " +
                "INSERT INTO deal_posting_intent_history " +
                "(idempotency_key, at, transition, actor_id, reason, value_date) " +
                "SELECT idempotency_key, ?1, 'CLOSED_BY_OPERATOR', ?2, ?3, value_date FROM u"

        const val CLAIM_SQL =
            "UPDATE deal_posting_intent SET claim_token = ?1, claimed_until = ?2 WHERE idempotency_key IN (" +
                "SELECT idempotency_key FROM deal_posting_intent WHERE status = 'OPEN' AND escalated_at IS NULL " +
                "AND created_at < ?3 " +
                "AND (claimed_until IS NULL OR claimed_until < ?4) ORDER BY created_at LIMIT ?5 " +
                "FOR UPDATE SKIP LOCKED)"

        const val SELECT_SQL =
            "SELECT idempotency_key, deal_id, event, action, value_date, actor_id, actor_type, reason, " +
                "command_key, created_at, attempts, last_error, status FROM deal_posting_intent"
    }
}
