// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.VelocityAggregate
import com.openbank.fraud.domain.model.VelocityWindow
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class VelocityAggregateRepositoryImpl(
    private val pool: PgPool,
    private val clock: Clock,
    private val metrics: FraudMetricsPort,
    @ConfigProperty(name = "openbank.fraud.applied-signal-window", defaultValue = "100")
    private val appliedSignalWindow: Int,
) : VelocityAggregateRepository {

    override suspend fun recordTransaction(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        transactionId: UUID?,
        occurredAt: Instant,
    ) {
        // Issue #6044: the bucket comes from the event's own time, never from Instant.now(clock).
        // window_start is part of the PK, so a redelivery processed on the other side of an hour
        // boundary would otherwise target a different ROW — and the applied-id guard below is per
        // row, so it would not see the first application and would count the replay again.
        VelocityWindow.entries.forEach { window ->
            val start = window.bucketStart(occurredAt).toOffsetDateTime()
            val result = pool.preparedQuery(UPSERT_SQL).execute(
                Tuple.of(
                    accountId.toString(),
                    window.name,
                    currency,
                    start,
                    amount,
                    transactionId?.toString(),
                ).addInteger(appliedSignalWindow),
            ).awaitSuspending()
            // rowCount 0 means the ON CONFLICT ... WHERE was false: this row has already applied
            // this transaction id, so the increment was suppressed. Nothing else records that.
            if (result.rowCount() == 0) {
                metrics.recordSignalReplaySuppressed(AGGREGATE_NAME)
            }
        }
    }

    /**
     * Returns the aggregate for [accountId] in [window] for [currency], or null if no data yet.
     * Currency is in the PK so each currency bucket is independent (no cross-currency amount mixing).
     */
    override suspend fun findAggregate(accountId: UUID, window: VelocityWindow, currency: String): VelocityAggregate? {
        val now = Instant.now(clock)
        val start = window.bucketStart(now)
        val rows = pool.preparedQuery(SELECT_SQL).execute(
            Tuple.of(accountId.toString(), window.name, currency, start.toOffsetDateTime()),
        ).awaitSuspending()
        val row = rows.firstOrNull() ?: return null
        return VelocityAggregate(
            accountId = accountId,
            window = window,
            transactionCount = row.getLong(0),
            totalAmount = row.getBigDecimal(1),
            currency = currency,
            windowStart = start,
        )
    }

    companion object {
        private const val AGGREGATE_NAME = "velocity_aggregates"

        // Redelivery guard, issue #5789 (superseding the #5716 last-writer marker). applied_transaction_ids
        // is the SET of ids this row has actually applied, most recent last, trimmed to the most recent
        // $7 entries by fraud_append_applied (V6). The WHERE tests membership of that set, so a replay is
        // suppressed however many other signals reached this row in between — the A, B, A ordering the
        // marker could not catch. A signal with no aggregateId (NULL) carries no identity to deduplicate
        // by and is applied unconditionally, exactly as before, and is not remembered.
        //
        // The marker is NOT merely diagnostic in the guard: it is unioned into the set being tested.
        // That covers the cutover window a backfill alone cannot — during a rolling deploy a pod still
        // running the V5/V3 code writes last_transaction_id and leaves applied_transaction_ids empty, so
        // a row touched after the migration but before the last old pod drains would otherwise carry no
        // memory of the id it just applied. Unioning makes the new guard strictly stronger than the old
        // one in every state, never weaker; outside that window the marker is always already the last
        // element of the set and the union adds nothing.
        // The marker is still maintained in last_transaction_id as a diagnostic, but nothing reads it as
        // a guard any more. The guard remains PER ROW, so the three window statements still converge
        // independently after a partial failure: a retry re-applies only the windows it had not reached.
        //
        // Residual bound, stated because it is a design decision and not an oversight: the set is bounded
        // by a COUNT of signals, not by elapsed time. A replay that arrives after $7 further signals to
        // this same (account, window, currency, bucket) row has been evicted from the set and is applied
        // again. See V6__applied_signal_ledger.sql for why a count window was chosen over a ledger table.
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
            INSERT INTO velocity_aggregates
                (account_id, velocity_window, currency, window_start, transaction_count, total_amount,
                 last_transaction_id, applied_transaction_ids, updated_at)
            VALUES ($1::uuid, $2, $3, $4, 1, $5, $6::uuid, array_remove(ARRAY[$6::uuid], NULL), NOW())
            ON CONFLICT (account_id, velocity_window, currency, window_start)
            DO UPDATE SET
                transaction_count       = velocity_aggregates.transaction_count + 1,
                total_amount            = velocity_aggregates.total_amount + EXCLUDED.total_amount,
                last_transaction_id     = EXCLUDED.last_transaction_id,
                applied_transaction_ids = fraud_append_applied(
                    velocity_aggregates.applied_transaction_ids, EXCLUDED.last_transaction_id, $7),
                updated_at              = NOW()
            WHERE EXCLUDED.last_transaction_id IS NULL
               OR NOT (EXCLUDED.last_transaction_id = ANY (
                       array_remove(
                           array_append(velocity_aggregates.applied_transaction_ids,
                                        velocity_aggregates.last_transaction_id), NULL)))
            """

        private const val SELECT_SQL =
            """
            SELECT transaction_count, total_amount
            FROM velocity_aggregates
            WHERE account_id = $1::uuid AND velocity_window = $2 AND currency = $3 AND window_start = $4
            """
    }
}

internal fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

private const val DAYS_PER_WEEK = 7L
private const val SECONDS_PER_DAY = 86400L
private const val SECONDS_PER_WEEK = DAYS_PER_WEEK * SECONDS_PER_DAY

internal fun VelocityWindow.bucketStart(now: Instant): Instant = when (this) {
    VelocityWindow.H1 -> now.truncatedTo(ChronoUnit.HOURS)
    VelocityWindow.H24 -> now.truncatedTo(ChronoUnit.DAYS)
    VelocityWindow.D7 -> {
        val weeks = now.truncatedTo(ChronoUnit.DAYS).epochSecond / SECONDS_PER_WEEK
        Instant.ofEpochSecond(weeks * SECONDS_PER_WEEK)
    }
}
