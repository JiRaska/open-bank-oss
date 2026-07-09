// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.domain.model.PayeeHistory
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed [PayeeHistoryRepository] (ADR-0084 §3 v4). Mirrors the upsert-per-signal shape
 * of [VelocityAggregateRepositoryImpl], with one addition: the upsert guards on
 * `last_transaction_id` so a redelivered/duplicate Kafka message for the same underlying
 * transaction does not double-count `payment_count`.
 */
@ApplicationScoped
class PayeeHistoryRepositoryImpl(private val pool: PgPool) : PayeeHistoryRepository {

    override suspend fun recordPayment(
        accountId: UUID,
        payeeIdentifier: String,
        transactionId: UUID?,
        occurredAt: Instant,
    ) {
        pool.preparedQuery(UPSERT_SQL).execute(
            Tuple.of(
                accountId.toString(),
                payeeIdentifier,
                occurredAt.toOffsetDateTime(),
                transactionId?.toString(),
            ),
        ).awaitSuspending()
    }

    override suspend fun findHistory(accountId: UUID, payeeIdentifier: String): PayeeHistory? {
        val rows = pool.preparedQuery(SELECT_SQL).execute(
            Tuple.of(accountId.toString(), payeeIdentifier),
        ).awaitSuspending()
        val row = rows.firstOrNull() ?: return null
        return PayeeHistory(
            accountId = accountId,
            payeeIdentifier = payeeIdentifier,
            firstSeenAt = row.getOffsetDateTime(0).toInstant(),
            lastPaidAt = row.getOffsetDateTime(1).toInstant(),
            paymentCount = row.getLong(2),
        )
    }

    companion object {
        // Idempotency guard: only increment when the incoming transaction id is either absent
        // (transactionId == NULL — the signal carries no aggregateId, so it cannot be deduplicated;
        // matches the existing velocity_aggregates path, which has no dedup at all) or genuinely
        // different from the last one recorded. A redelivered Kafka message for the SAME
        // transactionId matches payee_history.last_transaction_id and the WHERE clause is false, so
        // the DO UPDATE is skipped entirely (Postgres leaves the existing row untouched) — no
        // double-count, and first_seen_at is preserved (only ever set on INSERT).
        private const val UPSERT_SQL =
            """
            INSERT INTO payee_history
                (account_id, payee_identifier, first_seen_at, last_paid_at, payment_count,
                 last_transaction_id, updated_at)
            VALUES ($1::uuid, $2, $3, $3, 1, $4::uuid, NOW())
            ON CONFLICT (account_id, payee_identifier)
            DO UPDATE SET
                last_paid_at        = EXCLUDED.last_paid_at,
                payment_count       = payee_history.payment_count + 1,
                last_transaction_id = EXCLUDED.last_transaction_id,
                updated_at          = NOW()
            WHERE EXCLUDED.last_transaction_id IS NULL
               OR payee_history.last_transaction_id IS DISTINCT FROM EXCLUDED.last_transaction_id
            """

        private const val SELECT_SQL =
            """
            SELECT first_seen_at, last_paid_at, payment_count
            FROM payee_history
            WHERE account_id = $1::uuid AND payee_identifier = $2
            """
    }
}
