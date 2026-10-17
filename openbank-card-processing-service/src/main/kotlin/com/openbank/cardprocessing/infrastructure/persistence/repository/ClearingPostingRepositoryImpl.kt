// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.ClearingPostingRepository
import com.openbank.cardprocessing.application.port.out.PostingBacklog
import com.openbank.cardprocessing.application.port.out.PostingStatus
import com.openbank.cardprocessing.application.port.out.RecordedClearing
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardClearingEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Posting state of applied clearings (V5). The writes are single statements: a re-driver tick that
 * dies half way leaves each row either claimed (its lease expires and it is found again) or
 * recorded, never half-written.
 */
@ApplicationScoped
class ClearingPostingRepositoryImpl : ClearingPostingRepository {

    override suspend fun findDue(now: Instant, maxAttempts: Int, limit: Int): List<RecordedClearing> =
        Panache.withSession {
            Panache.getSession().chain { session ->
                session.createQuery(
                    "from CardClearingEntity where postingStatus <> 'POSTED' and postingAttempts < :max " +
                        "and (postingNextAttemptAt is null or postingNextAttemptAt <= :now) " +
                        "order by postingNextAttemptAt asc",
                    CardClearingEntity::class.java,
                )
                    .setParameter("max", maxAttempts)
                    .setParameter("now", now)
                    .setMaxResults(limit.coerceAtLeast(1))
                    .resultList
            }
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun claim(clearingId: UUID, now: Instant, leaseUntil: Instant, maxAttempts: Int): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>(
                    "UPDATE card_clearings SET posting_next_attempt_at = :lease " +
                        "WHERE id = :id AND posting_status <> 'POSTED' AND posting_attempts < :max " +
                        "AND (posting_next_attempt_at IS NULL OR posting_next_attempt_at <= :now)",
                )
                    .setParameter("lease", utc(leaseUntil))
                    .setParameter("id", clearingId)
                    .setParameter("max", maxAttempts)
                    .setParameter("now", utc(now))
                    .executeUpdate()
            }
        }.awaitSuspending() == 1

    override suspend fun recordAttempt(
        clearingId: UUID,
        status: PostingStatus,
        transactionId: UUID?,
        error: String?,
        now: Instant,
        nextAttemptAt: Instant?,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>(
                    "UPDATE card_clearings SET posting_status = :status, " +
                        "posting_attempts = posting_attempts + 1, posting_last_error = :error, " +
                        "posting_last_attempt_at = :now, posting_next_attempt_at = :next, " +
                        "posting_transaction_id = COALESCE(:tx, posting_transaction_id) WHERE id = :id",
                )
                    .setParameter("status", status.name)
                    .setParameter("error", error?.take(MAX_ERROR_LENGTH))
                    .setParameter("now", utc(now))
                    .setParameter("next", nextAttemptAt?.let { utc(it) })
                    .setParameter("tx", transactionId)
                    .setParameter("id", clearingId)
                    .executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun backlog(maxAttempts: Int): PostingBacklog = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery(
                "SELECT count(*) FILTER (WHERE posting_status <> 'POSTED'), " +
                    "count(*) FILTER (WHERE posting_status <> 'POSTED' AND posting_attempts >= :max) " +
                    "FROM card_clearings",
                Array<Any>::class.java,
            )
                .setParameter("max", maxAttempts)
                .singleResult
        }
    }.awaitSuspending().let { row ->
        PostingBacklog(unposted = (row[0] as Number).toLong(), exhausted = (row[1] as Number).toLong())
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun CardClearingEntity.toDomain() = RecordedClearing(
        id = id,
        authorizationId = authorizationId,
        idempotencyKey = idempotencyKey,
        requestFingerprint = requestFingerprint,
        amountMinorUnits = amountMinorUnits,
        currencyCode = currencyCode,
        appliedAt = appliedAt,
        postingStatus = PostingStatus.valueOf(postingStatus),
        postingAttempts = postingAttempts,
        postingNextAttemptAt = postingNextAttemptAt,
    )

    private companion object {
        /** `posting_last_error` is VARCHAR(512). */
        const val MAX_ERROR_LENGTH = 512
    }
}
