// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.VelocityWindow
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

    /**
     * The business time carried by every signal a test records, fixed once per test instance (JUnit
     * builds a fresh one per test). Fixing it matters twice over: the bucket is now derived from the
     * event's own time (#6044), and holding it constant stops a test that happens to straddle an
     * hour boundary mid-run from splitting its own writes across two rows.
     */
    private val eventTime: Instant = Instant.now(Clock.systemUTC())

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

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, UUID.randomUUID(), eventTime)

        assertAllWindows(accountId, count = 1L, total = "100.00")
    }

    @Test
    fun `a redelivered signal does not double-count the amount or the count`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, eventTime)
        // Redelivery of the exact same Kafka message (same aggregateId) — must be a no-op.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, eventTime)
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, eventTime)

        assertAllWindows(accountId, count = 1L, total = "100.00")
    }

    @Test
    fun `a genuinely new signal after a redelivery still counts`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val firstTransactionId = UUID.randomUUID()
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, firstTransactionId, eventTime)
        // Replay of the first signal — must not count.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, firstTransactionId, eventTime)

        // A genuinely different transaction — must count.
        repository.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, UUID.randomUUID(), eventTime)

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
        val h1Start = VelocityWindow.H1.bucketStart(eventTime)

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
        repository.recordTransaction(accountId, BigDecimal("70.00"), CURRENCY, transactionId, eventTime)

        assertAllWindows(accountId, count = 1L, total = "70.00")
    }

    @Test
    fun `a signal without a transaction id is never deduplicated`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()

        // No aggregateId on the wire means no identity to deduplicate by — the pre-#5716 behaviour
        // is preserved rather than silently dropping the second signal. Same contract as
        // payee_history.
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null, eventTime)
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null, eventTime)

        assertAllWindows(accountId, count = 2L, total = "20.00")
    }

    /**
     * Issue #5789 follow-up: a signal with no aggregateId must not POISON the row.
     *
     * The dedupe set is tested as `EXCLUDED.last_transaction_id = ANY (array_append(applied_ids,
     * last_transaction_id))`, and `last_transaction_id` is NULL for the whole life of the row after a
     * signal that carried no aggregateId. `x = ANY (array containing NULL)` is NULL, not FALSE, in
     * Postgres — so `NOT (...)` is NULL, the `ON CONFLICT ... WHERE` is not true, and every later
     * genuinely-new signal to that row is silently dropped forever. The row freezes.
     *
     * The sequence must INTERLEAVE: two NULLs back to back both take the
     * `EXCLUDED.last_transaction_id IS NULL` branch, which short-circuits before the membership test
     * is ever reached, so a repeated-NULL test cannot see this. C, NULL, D, E can: the NULL parks a
     * NULL in `last_transaction_id`, and D and E then have to survive the membership test.
     *
     * Direction of harm: velocity counts are UNDERCOUNTED, so a velocity rule that should fire does
     * not. Against the pre-#5789 `IS DISTINCT FROM` guard this is a REGRESSION — that form was
     * NULL-safe.
     */
    @Test
    fun `a signal without a transaction id does not freeze the row against later signals`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, UUID.randomUUID(), eventTime)
        // No aggregateId: applied unconditionally, and deliberately not remembered.
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null, eventTime)
        // Two genuinely new signals, after the NULL. Both must still count.
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, UUID.randomUUID(), eventTime)
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, UUID.randomUUID(), eventTime)

        assertAllWindows(accountId, count = 4L, total = "40.00")
    }

    /**
     * The guard must still SUPPRESS after a NULL has passed through — a NULL-safe membership test
     * that simply always evaluated true would pass the freeze test above while silently disabling
     * dedupe. This is the other side of that: replay of C after the NULL is still a replay.
     */
    @Test
    fun `dedupe still suppresses a replay after a signal without a transaction id`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val c = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, c, eventTime)
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, null, eventTime)
        // Replay of C, after the NULL. Must be suppressed.
        repository.recordTransaction(accountId, BigDecimal("10.00"), CURRENCY, c, eventTime)

        assertAllWindows(accountId, count = 2L, total = "20.00")
    }

    @Test
    fun `currencies are deduplicated independently`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, eventTime)
        // A different currency is a different row, so the same id does not suppress it.
        repository.recordTransaction(accountId, BigDecimal("100.00"), "EUR", transactionId, eventTime)

        assertAllWindows(accountId, count = 1L, total = "100.00")
        val eur = repository.findAggregate(accountId, VelocityWindow.H1, "EUR")
        assertThat(eur!!.transactionCount).isEqualTo(1L)
        assertThat(eur.totalAmount).isEqualByComparingTo("100.00")
    }

    /**
     * Issue #5789 — the defect the V5 last-writer marker could not catch. The marker stored the LAST
     * id applied, not the set of ids applied, so an A, B, A delivery order found the marker holding
     * B when the replayed A arrived, `IS DISTINCT FROM` it, and applied A a second time. This is not
     * an exotic ordering: `openbank.transactions.transaction.initiated` is keyed by the transaction
     * aggregateId, so two signals for one account are two different keys, and any at-least-once
     * replay of an uncommitted offset window re-delivers A after B has already been applied.
     *
     * Correct total after A, B, A is two applications, not three.
     */
    @Test
    fun `an out-of-order replay after another signal is not applied twice`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)
        repository.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, b, eventTime)
        // The replay of A, landing after B — the last-writer marker holds B here and let this through.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)

        assertAllWindows(accountId, count = 2L, total = "140.00")
    }

    /** The same, one step longer: both ids replayed after the other, in the order Kafka replays them. */
    @Test
    fun `replaying a whole uncommitted window applies neither signal twice`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)
        repository.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, b, eventTime)
        // Rebalance: the consumer resumes from the last committed offset and re-delivers A then B.
        repository.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)
        repository.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, b, eventTime)

        assertAllWindows(accountId, count = 2L, total = "140.00")
    }

    /**
     * A suppressed replay writes no row, logs nothing and moves no counter, so before #5789 the guard
     * working and the guard being absent were indistinguishable from outside the database. This is the
     * series that tells them apart: one increment per suppressed row-write, i.e. three per suppressed
     * signal (one per velocity window).
     */
    @Test
    fun `a suppressed replay is counted`(): Unit = runBlocking {
        val suppressed = mutableListOf<String>()
        val repo = VelocityAggregateRepositoryImpl(pool, Clock.systemUTC(), recordingMetrics(suppressed), 100)
        val accountId = UUID.randomUUID()
        val a = UUID.randomUUID()

        repo.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)
        assertThat(suppressed).describedAs("a first application is not a suppression").isEmpty()

        repo.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)

        assertThat(suppressed).containsExactly("velocity_aggregates", "velocity_aggregates", "velocity_aggregates")
    }

    /**
     * The stated residual bound, asserted rather than only documented: the applied-signal set is
     * bounded by a COUNT of signals to the same row, not by elapsed time. With the window set to 1 the
     * guard degrades exactly to the old last-writer marker, and the A, B, A replay double-counts again
     * — which is what the default of 100 buys, and what a smaller value would give back.
     */
    @Test
    fun `the applied-signal window bounds how far back a replay can be suppressed`(): Unit = runBlocking {
        val repo = VelocityAggregateRepositoryImpl(pool, Clock.systemUTC(), noopMetrics(), 1)
        val accountId = UUID.randomUUID()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repo.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)
        repo.recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, b, eventTime)
        repo.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, a, eventTime)

        // A has been evicted from a one-entry set, so it applies again: 3 applications, 240.00.
        assertAllWindows(accountId, count = 3L, total = "240.00")
    }

    /**
     * Issue #6044 — the half of #5789 the applied-id set cannot reach, because it is not a dedupe
     * defect at all: the bucket used to come from `Instant.now(clock)` at PROCESSING time, and
     * `window_start` is part of the primary key. A signal that occurred at 10:59 and is redelivered
     * at 11:00 therefore hit a DIFFERENT ROW from the original, and the applied-id set lives on the
     * row — so the replay met an empty set, was not recognised, and was counted a second time. No
     * value of `openbank.fraud.applied-signal-window` changes that; the set it consults is the wrong
     * row's.
     *
     * The delivery is built here exactly as it happens: the SAME signal (same id, same `occurredAt`,
     * one minute before the hour) applied twice, with the two repository instances differing ONLY in
     * the clock they would have bucketed by — 10:59 for the original delivery, 11:00 for the replay.
     * With the bucket taken from `occurredAt` both target the 10:00 row, the guard sees the id, and
     * the correct total is one application.
     */
    @Test
    fun `a replay processed after the hour boundary is not counted again in the next bucket`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-07-09T10:59:00Z")

        val atOriginalDelivery = repositoryAt("2026-07-09T10:59:01Z")
        val afterTheBoundary = repositoryAt("2026-07-09T11:00:02Z")

        atOriginalDelivery.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, occurredAt)
        // The redelivery: same event, same occurredAt, processed on the other side of 11:00.
        afterTheBoundary.recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, transactionId, occurredAt)

        // The double-count, stated independently of which rows exist: across EVERY H1 bucket this
        // account has, the signal must have been applied exactly once. Asserting only the 10:00 row
        // would miss the defect — under processing-time bucketing that row also reads 1, and the
        // second application sits in a row the assertion never looks at.
        assertAppliedAcrossAllBuckets(accountId, VelocityWindow.H1, count = 1L, total = "100.00")
        // Both applications landed on the bucket the event belongs to...
        assertBucket(accountId, VelocityWindow.H1, occurredAt, count = 1L, total = "100.00")
        // ...and nothing at all was written into the 11:00 bucket the replay used to create.
        assertNoBucket(accountId, VelocityWindow.H1, Instant.parse("2026-07-09T11:00:02Z"))
    }

    /**
     * The same defect one level up: it is not only replays. Two signals that OCCURRED in the same
     * hour but were processed either side of the boundary — an ordinary consumer lag or pod roll —
     * used to be split across two rows, so neither the H1 count nor the H1 sum the scorer reads was
     * ever the truth about that hour. Event-time bucketing puts both where they happened.
     */
    @Test
    fun `two signals from the same hour processed either side of the boundary land in one bucket`(): Unit =
        runBlocking {
            val accountId = UUID.randomUUID()
            val hour = Instant.parse("2026-07-09T10:00:00Z")

            repositoryAt("2026-07-09T10:20:00Z")
                .recordTransaction(accountId, BigDecimal("100.00"), CURRENCY, UUID.randomUUID(), hour.plusSeconds(600))
            repositoryAt("2026-07-09T11:00:30Z")
                .recordTransaction(accountId, BigDecimal("40.00"), CURRENCY, UUID.randomUUID(), hour.plusSeconds(3000))

            assertBucket(accountId, VelocityWindow.H1, hour, count = 2L, total = "140.00")
        }

    /** A repository whose clock reads [instant] — the moment a signal is PROCESSED, nothing else. */
    private fun repositoryAt(instant: String): VelocityAggregateRepository = VelocityAggregateRepositoryImpl(
        pool,
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        noopMetrics(),
        100,
    )

    /** Reads one bucket row directly — [findAggregate] can only ask about the CURRENT window. */
    private suspend fun selectBucket(accountId: UUID, window: VelocityWindow, at: Instant): Row? = pool.preparedQuery(
        """
            SELECT transaction_count, total_amount
            FROM velocity_aggregates
            WHERE account_id = $1::uuid AND velocity_window = $2 AND currency = $3 AND window_start = $4
            """,
    ).execute(
        Tuple.of(accountId.toString(), window.name, CURRENCY, window.bucketStart(at).toOffsetDateTime()),
    ).awaitSuspending().firstOrNull()

    private suspend fun assertBucket(accountId: UUID, window: VelocityWindow, at: Instant, count: Long, total: String) {
        val row = selectBucket(accountId, window, at)
        assertThat(row).describedAs("row for the %s bucket containing %s", window, at).isNotNull
        assertThat(row!!.getLong(0)).describedAs("count in the bucket containing %s", at).isEqualTo(count)
        assertThat(
            row.getBigDecimal(1),
        ).describedAs("total in the bucket containing %s", at).isEqualByComparingTo(total)
    }

    /** Every bucket row for this (account, window, currency), summed — where a double-count shows up. */
    private suspend fun assertAppliedAcrossAllBuckets(
        accountId: UUID,
        window: VelocityWindow,
        count: Long,
        total: String,
    ) {
        val row = pool.preparedQuery(
            """
            SELECT COALESCE(SUM(transaction_count), 0), COALESCE(SUM(total_amount), 0)
            FROM velocity_aggregates
            WHERE account_id = $1::uuid AND velocity_window = $2 AND currency = $3
            """,
        ).execute(Tuple.of(accountId.toString(), window.name, CURRENCY)).awaitSuspending().first()
        assertThat(row.getLong(0)).describedAs("applications across all %s buckets", window).isEqualTo(count)
        assertThat(row.getBigDecimal(1)).describedAs("total across all %s buckets", window).isEqualByComparingTo(total)
    }

    private suspend fun assertNoBucket(accountId: UUID, window: VelocityWindow, at: Instant) {
        assertThat(selectBucket(accountId, window, at))
            .describedAs("no %s row should exist for the bucket containing %s", window, at)
            .isNull()
    }

    private fun recordingMetrics(sink: MutableList<String>): FraudMetricsPort = object : FraudMetricsPort {
        override fun recordVerdict(verdict: FraudVerdict, rail: String) = Unit
        override fun recordShadowScore(score: Double) = Unit
        override fun recordSignalReplaySuppressed(aggregate: String) {
            sink.add(aggregate)
        }

        override fun recordSignalMissingEventTime() = Unit
    }

    private fun noopMetrics(): FraudMetricsPort = recordingMetrics(mutableListOf())

    private companion object {
        const val CURRENCY = "CZK"
    }
}
