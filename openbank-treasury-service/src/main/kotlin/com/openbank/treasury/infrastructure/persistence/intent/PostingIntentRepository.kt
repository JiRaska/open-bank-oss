// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.intent

import com.openbank.treasury.application.port.out.OpenIntentSummary
import com.openbank.treasury.application.port.out.PostingIntent
import com.openbank.treasury.application.port.out.PostingIntentAction
import com.openbank.treasury.application.port.out.PostingIntentPort
import com.openbank.treasury.application.port.out.PostingIntentStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.PostingEvent
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
    override suspend fun open(intent: PostingIntent): PostingIntent = sf.withTransaction { s ->
        write(s, INSERT_SQL, intent)
            .flatMap { inserted ->
                if (inserted == 1) {
                    Uni.createFrom().item(true)
                } else {
                    write(s, REOPEN_SQL, intent).map { it == 1 }
                }
            }
            .flatMap { fresh ->
                s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE idempotency_key = ?1")
                    .setParameter(1, intent.idempotencyKey)
                    .singleResult
                    .map { it.toIntent().copy(fresh = fresh) }
            }
    }.awaitSuspending()

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
        update(
            "UPDATE deal_posting_intent SET status = 'REFUSED', closed_at = ?1, last_error = ?2, " +
                "claim_token = NULL, claimed_until = NULL WHERE idempotency_key = ?3 AND status = 'OPEN'",
            utc(clock.instant()),
            reason,
            key,
        )
    }

    override suspend fun failed(key: String, error: String) {
        update(
            "UPDATE deal_posting_intent SET attempts = attempts + 1, last_error = ?1, " +
                "claim_token = NULL, claimed_until = NULL WHERE idempotency_key = ?2 AND status = 'OPEN'",
            error,
            key,
        )
    }

    override suspend fun closeByOperator(key: String, actor: Actor, reason: String): Boolean = update(
        "UPDATE deal_posting_intent SET status = 'REFUSED', closed_at = ?1, closed_by = ?2, last_error = ?3, " +
            "claim_token = NULL, claimed_until = NULL WHERE idempotency_key = ?4 AND status = 'OPEN'",
        utc(clock.instant()),
        actor.id,
        "closed by operator: $reason",
        key,
    ) == 1

    private suspend fun update(sql: String, vararg params: Any?): Int = sf.withTransaction { s ->
        params.foldIndexed(s.createNativeQuery<Int>(sql)) { i, q, v -> q.setParameter(i + 1, v) }.executeUpdate()
    }.awaitSuspending()

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

        const val INSERT_SQL =
            "INSERT INTO deal_posting_intent (idempotency_key, deal_id, event, action, value_date, actor_id, " +
                "actor_type, reason, command_key, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) " +
                "ON CONFLICT (idempotency_key) DO NOTHING"

        // Only a REFUSED row is reopened, and it takes everything from the new attempt, its value day
        // included. An OPEN row keeps its first attempt's value day: that attempt may have posted.
        const val REOPEN_SQL =
            "UPDATE deal_posting_intent SET status = 'OPEN', closed_at = NULL, closed_by = NULL, " +
                "last_error = NULL, attempts = 0, deal_id = ?2, event = ?3, action = ?4, value_date = ?5, " +
                "actor_id = ?6, actor_type = ?7, reason = ?8, command_key = ?9, created_at = ?10 " +
                "WHERE idempotency_key = ?1 AND status = 'REFUSED'"

        const val CLAIM_SQL =
            "UPDATE deal_posting_intent SET claim_token = ?1, claimed_until = ?2 WHERE idempotency_key IN (" +
                "SELECT idempotency_key FROM deal_posting_intent WHERE status = 'OPEN' AND created_at < ?3 " +
                "AND (claimed_until IS NULL OR claimed_until < ?4) ORDER BY created_at LIMIT ?5 " +
                "FOR UPDATE SKIP LOCKED)"

        const val SELECT_SQL =
            "SELECT idempotency_key, deal_id, event, action, value_date, actor_id, actor_type, reason, " +
                "command_key, created_at, attempts, last_error, status FROM deal_posting_intent"
    }
}
