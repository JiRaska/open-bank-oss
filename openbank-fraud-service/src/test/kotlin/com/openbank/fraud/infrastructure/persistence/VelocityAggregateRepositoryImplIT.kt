// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.VelocityWindow
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres acceptance test for the #5716 redelivery guard on `velocity_aggregates`
 * (V5__velocity_aggregates_redelivery_dedup.sql + [VelocityAggregateRepositoryImpl]). The sibling
 * [VelocityAggregateRepositoryImplTest] mocks `PgPool` and can only assert that a query was issued —
 * it cannot tell an upsert that double-counts from one that does not. Only the real upsert SQL
 * against a real database can, which is why this class exists alongside it (same reason
 * [PayeeHistoryRepositoryImplIT] exists for the V3 guard).
 *
 * Each test uses a fresh random account id, so the three window rows it touches are its own — no
 * cross-test interference and no cleanup needed.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class VelocityAggregateRepositoryImplIT {

    @Inject
    lateinit var repository: VelocityAggregateRepository

    @Inject
    lateinit var pool: PgPool

    private suspend fun assertAllWindows(accountId: UUID, count: Long, total: String) {
        VelocityWindow.entries.forEach { window ->
            val aggregate = repository.findAggregate(accountId, window, CURRENCY)
            assertThat(aggregate)
                .describedAs("aggregate for window %s", window)
                .isNotNull
            assertThat(aggregate!!.transactionCount).describedAs("count for window %s", window).isEqualTo(count)
            assertThat(aggregate.totalAmount).describedAs("total for window %s", window).isEqualByComparingTo(total)
        }
    }

    @Test
    fun `a first signal is recorded in every window`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, UUID.randomUUID())

        assertAllWindows(accountId, count = 1L, total = "100.00")
    }

    @Test
    fun `a redelivered signal does not double-count the amount or the count`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId)
        // Redelivery of the exact same Kafka message (same aggregateId) — must be a no-op.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId)
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId)

        assertAllWindows(accountId, count = 1L, total = "100.00")
    }

    @Test
    fun `a genuinely new signal after a redelivery still counts`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val firstTransactionId = UUID.randomUUID()
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, firstTransactionId)
        // Replay of the first signal — must not count.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, firstTransactionId)

        // A genuinely different transaction — must count.
        repository.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, UUID.randomUUID())

        assertAllWindows(accountId, count = 2L, total = "140.00")
    }

    /**
     * The per-window partial-failure case — the half of #5716 that the guard alone does not settle.
     * `recordTransaction` writes one row per window and the three are deliberately NOT one
     * transaction, so a crash between them leaves some windows applied and others not. That state is
     * built here directly through [PgPool] (the port cannot express "apply to one window only"):
     * only the H1 row is written, carrying the signal's id, as if the process died before reaching
     * H24 and D7. The retry then re-runs the full loop and must converge — H1 skipped because it
     * already applied this id, H24 and D7 applied for the first time — leaving exactly one
     * application in every window.
     */
    @Test
    fun `a retry after a partial per-window failure converges to the correct total`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val h1Start = VelocityWindow.H1.bucketStart(Instant.now(Clock.systemUTC()))

        // The interrupted first attempt: H1 applied, H24 and D7 never reached.
        pool.preparedQuery(
            """
            INSERT INTO velocity_aggregates
                (account_id, velocity_window, currency, window_start, transaction_count, total_amount,
                 last_transaction_id, updated_at)
            VALUES ($1::uuid, 'H1', $2, $3, 1, $4, $5::uuid, NOW())
            """,
        ).execute(
            Tuple.of(
                accountId.toString(),
                CURRENCY,
                h1Start.toOffsetDateTime(),
                BigDecimal("70.00"),
                transactionId.toString(),
            ),
        ).awaitSuspending()

        // The redelivery re-runs the full per-window loop.
        repository.recordTransaction(accountId, BigDecimal("70.00"), CURRENCY, transactionId)

        assertAllWindows(accountId, count = 1L, total = "70.00")
    }

    @Test
    fun `a signal without a transaction id is never deduplicated`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()

        // No aggregateId on the wire means no identity to deduplicate by — the pre-#5716 behaviour
        // is preserved rather than silently dropping the second signal. Same contract as
        // payee_history.
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null)
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null)

        assertAllWindows(accountId, count = 2L, total = "20.00")
    }

    @Test
    fun `currencies are deduplicated independently`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId)
        // A different currency is a different row, so the same id does not suppress it.
        repository.recordTransaction(accountId, BigDecimal("100.00"), "EUR", transactionId)

        assertAllWindows(accountId, count = 1L, total = "100.00")
        val eur = repository.findAggregate(accountId, VelocityWindow.H1, "EUR")
        assertThat(eur!!.transactionCount).isEqualTo(1L)
        assertThat(eur.totalAmount).isEqualByComparingTo("100.00")
    }

    private companion object {
        const val CURRENCY = "CZK"
    }
}
