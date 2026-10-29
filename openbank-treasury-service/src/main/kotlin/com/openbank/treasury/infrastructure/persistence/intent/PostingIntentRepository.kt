// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.intent

import com.openbank.treasury.application.port.out.PostingIntent
import com.openbank.treasury.application.port.out.PostingIntentAction
import com.openbank.treasury.application.port.out.PostingIntentPort
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.PostingEvent
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
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
class PostingIntentRepository(private val sf: Mutiny.SessionFactory) : PostingIntentPort {

    override suspend fun open(intent: PostingIntent): PostingIntent = sf.withTransaction { s ->
        s.createNativeQuery<Int>(OPEN_SQL)
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
            .flatMap {
                s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE idempotency_key = ?1")
                    .setParameter(1, intent.idempotencyKey)
                    .singleResult
            }
    }.awaitSuspending().toIntent()

    override suspend fun refuse(key: String, reason: String) {
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                "UPDATE deal_posting_intent SET status = 'REFUSED', closed_at = now(), last_error = ?1 " +
                    "WHERE idempotency_key = ?2 AND status = 'OPEN'",
            )
                .setParameter(1, reason)
                .setParameter(2, key)
                .executeUpdate()
        }.awaitSuspending()
    }

    override suspend fun failed(key: String, error: String) {
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                "UPDATE deal_posting_intent SET attempts = attempts + 1, last_error = ?1 " +
                    "WHERE idempotency_key = ?2 AND status = 'OPEN'",
            )
                .setParameter(1, error)
                .setParameter(2, key)
                .executeUpdate()
        }.awaitSuspending()
    }

    override suspend fun openBefore(cutoff: Instant): List<PostingIntent> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>("$SELECT_SQL WHERE status = 'OPEN' AND created_at < ?1 ORDER BY created_at")
            .setParameter(1, utc(cutoff))
            .resultList
    }.awaitSuspending().map { it.toIntent() }

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

        // A retry of a REFUSED post reopens it as a fresh attempt by its new caller; an OPEN or
        // COMPLETED one is kept as it stands. The value day is never changed, so every attempt asks the ledger for the same journal.
        const val OPEN_SQL =
            "INSERT INTO deal_posting_intent (idempotency_key, deal_id, event, action, value_date, actor_id, " +
                "actor_type, reason, command_key, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) " +
                "ON CONFLICT (idempotency_key) DO UPDATE SET status = 'OPEN', closed_at = NULL, " +
                "actor_id = EXCLUDED.actor_id, actor_type = EXCLUDED.actor_type, reason = EXCLUDED.reason, " +
                "command_key = EXCLUDED.command_key, created_at = EXCLUDED.created_at " +
                "WHERE deal_posting_intent.status = 'REFUSED'"

        const val SELECT_SQL =
            "SELECT idempotency_key, deal_id, event, action, value_date, actor_id, actor_type, reason, " +
                "command_key, created_at, attempts, last_error FROM deal_posting_intent"
    }
}
