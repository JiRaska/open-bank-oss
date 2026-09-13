// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.domain.model.PayeeHistory
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed [PayeeHistoryRepository] (ADR-0084 §3 v4). Mirrors the upsert-per-signal shape
 * of [VelocityAggregateRepositoryImpl], with one addition: the upsert guards on
 * `last_transaction_id` so a redelivered/duplicate Kafka message for the same underlying
 * transaction does not double-count `payment_count`.
 */
@ApplicationScoped
class PayeeHistoryRepositoryImpl(
    private val pool: PgPool,
    private val metrics: FraudMetricsPort,
    @ConfigProperty(name = "openbank.fraud.applied-signal-window", defaultValue = "100")
    private val appliedSignalWindow: Int,
) : PayeeHistoryRepository {

    override suspend fun recordPayment(
        accountId: UUID,
        payeeIdentifier: String,
        transactionId: UUID?,
        occurredAt: Instant,
    ) {
        val result = pool.preparedQuery(UPSERT_SQL).execute(
            Tuple.of(
                accountId.toString(),
                payeeIdentifier,
                occurredAt.toOffsetDateTime(),
                transactionId?.toString(),
            ).addInteger(appliedSignalWindow),
        ).awaitSuspending()
        // rowCount 0 means the ON CONFLICT ... WHERE was false: this (account, payee) row has
        // already applied this transaction id and the increment was suppressed.
        if (result.rowCount() == 0) {
            metrics.recordSignalReplaySuppressed(AGGREGATE_NAME)
        }
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
        private const val AGGREGATE_NAME = "payee_history"

        // Idempotency guard, issue #5789 (superseding the V3 last-writer marker, which has shipped
        // since V3 and only ever caught a replay that was consecutive for this row).
        // applied_transaction_ids is the SET of ids this (account, payee) row has actually applied,
        // trimmed to the most recent $5 entries by fraud_append_applied (V6); the WHERE tests
        // membership of that set, so a replay is suppressed however many other payments to the same
        // payee landed in between. A signal with no transaction id (NULL) carries no identity to
        // deduplicate by, is applied unconditionally as before, and is not remembered.
        //
        // The marker is NOT merely diagnostic in the guard: it is unioned into the set being tested.
        // That covers the cutover window a backfill alone cannot — during a rolling deploy a pod still
        // running the V5/V3 code writes last_transaction_id and leaves applied_transaction_ids empty, so
        // a row touched after the migration but before the last old pod drains would otherwise carry no
        // memory of the id it just applied. Unioning makes the new guard strictly stronger than the old
        // one in every state, never weaker; outside that window the marker is always already the last
        // element of the set and the union adds nothing.
        // first_seen_at is still only ever set on INSERT, so a suppressed replay cannot move it.
        // Residual bound (a decision, not an oversight): the set is bounded by a COUNT of signals,
        // not by elapsed time — a replay arriving after $5 further payments to the same payee is
        // applied again. See V6__applied_signal_ledger.sql.
        // NULL-safety of the membership test (the union's cost, and the reason for array_remove):
        // last_transaction_id is NULL for the whole life of a row after a signal that carried no
        // aggregateId, and `x = ANY (array containing NULL)` evaluates to NULL — not FALSE — in
        // Postgres. Without array_remove, `NOT (...)` is then NULL, the ON CONFLICT ... WHERE is not
        // true, and every later genuinely-new signal to that row is silently dropped forever: the row
        // freezes and the counts UNDERCOUNT, so a rule that should fire does not. The old
        // IS DISTINCT FROM guard was NULL-safe, so this would have been a regression against main.
        // applied_transaction_ids itself can never hold a NULL element (the INSERT path array_removes
        // it, fraud_append_applied refuses to append it, and the V6 backfill array_removes it), so the
        // union with the scalar marker is the only way a NULL reaches this array — array_remove is
        // scoped exactly to that, and a NULL signal stays "applied unconditionally, not remembered".
        private const val UPSERT_SQL =
            """
            INSERT INTO payee_history
                (account_id, payee_identifier, first_seen_at, last_paid_at, payment_count,
                 last_transaction_id, applied_transaction_ids, updated_at)
            VALUES ($1::uuid, $2, $3, $3, 1, $4::uuid, array_remove(ARRAY[$4::uuid], NULL), NOW())
            ON CONFLICT (account_id, payee_identifier)
            DO UPDATE SET
                last_paid_at            = EXCLUDED.last_paid_at,
                payment_count           = payee_history.payment_count + 1,
                last_transaction_id     = EXCLUDED.last_transaction_id,
                applied_transaction_ids = fraud_append_applied(
                    payee_history.applied_transaction_ids, EXCLUDED.last_transaction_id, $5),
                updated_at              = NOW()
            WHERE EXCLUDED.last_transaction_id IS NULL
               OR NOT (EXCLUDED.last_transaction_id = ANY (
                       array_remove(
                           array_append(payee_history.applied_transaction_ids,
                                        payee_history.last_transaction_id), NULL)))
            """

        private const val SELECT_SQL =
            """
            SELECT first_seen_at, last_paid_at, payment_count
            FROM payee_history
            WHERE account_id = $1::uuid AND payee_identifier = $2
            """
    }
}
