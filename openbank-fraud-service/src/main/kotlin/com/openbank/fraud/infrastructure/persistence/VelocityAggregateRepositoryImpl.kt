// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.VelocityAggregate
import com.openbank.fraud.domain.model.VelocityWindow
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class VelocityAggregateRepositoryImpl(private val pool: PgPool, private val clock: Clock) :
    VelocityAggregateRepository {

    override suspend fun recordTransaction(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        transactionId: UUID?,
    ) {
        val now = Instant.now(clock)
        VelocityWindow.entries.forEach { window ->
            val start = window.bucketStart(now).toOffsetDateTime()
            pool.preparedQuery(UPSERT_SQL).execute(
                Tuple.of(
                    accountId.toString(),
                    window.name,
                    currency,
                    start,
                    amount,
                    transactionId?.toString(),
                ),
            ).awaitSuspending()
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
        // Redelivery guard (#5716), mirroring payee_history's: only apply when the incoming
        // transaction id is either absent (NULL — the signal carries no aggregateId, so it cannot be
        // deduplicated) or genuinely different from the one this row last applied. A redelivered
        // Kafka message for the SAME aggregateId makes the WHERE false, so the DO UPDATE is skipped
        // entirely and Postgres leaves the row untouched — no double-count of either the count or
        // the amount. The marker is per row, so the three window statements converge independently:
        // a retry after a partial failure re-applies only the windows it had not reached.
        private const val UPSERT_SQL =
            """
            INSERT INTO velocity_aggregates
                (account_id, velocity_window, currency, window_start, transaction_count, total_amount,
                 last_transaction_id, updated_at)
            VALUES ($1::uuid, $2, $3, $4, 1, $5, $6::uuid, NOW())
            ON CONFLICT (account_id, velocity_window, currency, window_start)
            DO UPDATE SET
                transaction_count   = velocity_aggregates.transaction_count + 1,
                total_amount        = velocity_aggregates.total_amount + EXCLUDED.total_amount,
                last_transaction_id = EXCLUDED.last_transaction_id,
                updated_at          = NOW()
            WHERE EXCLUDED.last_transaction_id IS NULL
               OR velocity_aggregates.last_transaction_id IS DISTINCT FROM EXCLUDED.last_transaction_id
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
