// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.domain.model.FraudVerdict
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.vertx.mutiny.pgclient.PgPool
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres acceptance test for the ADR-0084 §3 v4 payee-history signal (V3__create_payee_history.sql
 * + [PayeeHistoryRepositoryImpl]). Unlike [VelocityAggregateRepositoryImplTest] (mocked `PgPool` —
 * asserts a query was issued), this runs the actual upsert/guard SQL against a real database so the
 * idempotent-replay behaviour is verified for real, not just "a query happened".
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class PayeeHistoryRepositoryImplIT {

    @Inject
    lateinit var repository: PayeeHistoryRepository

    @Inject
    lateinit var pool: PgPool

    @Test
    fun `a payee with no recorded payment is new (findHistory returns null)`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()

        val history = repository.findHistory(accountId, payeeIdentifier)

        assertThat(history).isNull()
    }

    @Test
    fun `transitions from new to established after the first payment is recorded`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        assertThat(repository.findHistory(accountId, payeeIdentifier)).isNull()

        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history).isNotNull
        assertThat(history!!.paymentCount).isEqualTo(1L)
    }

    @Test
    fun `second payment to the same established payee increments the count`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())

        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(2L)
    }

    @Test
    fun `replaying the same transaction id is idempotent and does not double-count`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID()
        val occurredAt = Instant.now()

        repository.recordPayment(accountId, payeeIdentifier, transactionId, occurredAt)
        // Redelivery of the exact same Kafka message (same aggregateId) — must be a no-op.
        repository.recordPayment(accountId, payeeIdentifier, transactionId, occurredAt)
        repository.recordPayment(accountId, payeeIdentifier, transactionId, occurredAt)

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(1L)
    }

    @Test
    fun `a genuinely new transaction id after a replay still increments once more`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val firstTransactionId = UUID.randomUUID()
        repository.recordPayment(accountId, payeeIdentifier, firstTransactionId, Instant.now())
        // Replay of the first payment — must not count.
        repository.recordPayment(accountId, payeeIdentifier, firstTransactionId, Instant.now())

        // A genuinely new payment (different transaction id) — must count.
        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(2L)
    }

    @Test
    fun `first_seen_at is preserved across subsequent payments`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val firstOccurredAt = Instant.parse("2026-01-01T00:00:00Z")
        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), firstOccurredAt)

        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.parse("2026-06-01T00:00:00Z"))

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.firstSeenAt).isEqualTo(firstOccurredAt)
        assertThat(history.lastPaidAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
    }

    @Test
    fun `different accounts paying the same payee identifier are tracked independently`(): Unit = runBlocking {
        val payeeIdentifier = UUID.randomUUID().toString()
        val accountA = UUID.randomUUID()
        val accountB = UUID.randomUUID()
        repository.recordPayment(accountA, payeeIdentifier, UUID.randomUUID(), Instant.now())

        val historyForB = repository.findHistory(accountB, payeeIdentifier)

        assertThat(historyForB).isNull()
    }

    /**
     * Issue #5789 follow-up, payee_history side of the same defect: a payment with no transaction id
     * parks a NULL in `last_transaction_id`, which is unioned into the array the membership test runs
     * over. `x = ANY (array containing NULL)` is NULL, so `NOT (...)` is NULL, the
     * `ON CONFLICT ... WHERE` is not true, and every later payment to that payee is silently dropped —
     * `payment_count` freezes. payee_history feeds first-time-payee detection, so a frozen row keeps a
     * genuine payee looking newer than it is.
     *
     * The sequence INTERLEAVES on purpose: consecutive NULLs both take the
     * `EXCLUDED.last_transaction_id IS NULL` branch and short-circuit before the membership test.
     */
    @Test
    fun `a payment without a transaction id does not freeze the row against later payments`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()

        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, null, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, UUID.randomUUID(), Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(4L)
    }

    /** The other side: dedupe must still suppress a replay once a NULL has passed through the row. */
    @Test
    fun `dedupe still suppresses a replay after a payment without a transaction id`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val c = UUID.randomUUID()

        repository.recordPayment(accountId, payeeIdentifier, c, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, null, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, c, Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(2L)
    }

    /**
     * Issue #5789 — the defect the V3 last-writer marker has never caught, and which was treated
     * throughout the #5698 sweep as "the one that already guards". The marker stored the LAST id
     * applied, so a replay of A arriving after B is `IS DISTINCT FROM` the marker and increments
     * `payment_count` a second time. payee_history feeds first-time-payee detection, so a spurious
     * re-application moves a fraud signal, not just a counter.
     */
    @Test
    fun `an out-of-order replay after another payment is not counted twice`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repository.recordPayment(accountId, payeeIdentifier, a, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, b, Instant.now())
        // The replay of A, landing after B.
        repository.recordPayment(accountId, payeeIdentifier, a, Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(2L)
    }

    /** Both ids replayed after each other, in the order an uncommitted offset window is re-delivered. */
    @Test
    fun `replaying a whole uncommitted window counts neither payment twice`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        repository.recordPayment(accountId, payeeIdentifier, a, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, b, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, a, Instant.now())
        repository.recordPayment(accountId, payeeIdentifier, b, Instant.now())

        val history = repository.findHistory(accountId, payeeIdentifier)
        assertThat(history!!.paymentCount).isEqualTo(2L)
    }

    /**
     * A suppressed replay leaves no trace of its own — no row written, nothing logged. This asserts
     * the one series that makes it visible.
     */
    @Test
    fun `a suppressed replay is counted`(): Unit = runBlocking {
        val suppressed = mutableListOf<String>()
        val repo = PayeeHistoryRepositoryImpl(
            pool,
            object : FraudMetricsPort {
                override fun recordVerdict(verdict: FraudVerdict, rail: String) = Unit
                override fun recordShadowScore(score: Double) = Unit
                override fun recordSignalReplaySuppressed(aggregate: String) {
                    suppressed.add(aggregate)
                }

                override fun recordSignalMissingEventTime() = Unit
            },
            100,
        )
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val a = UUID.randomUUID()

        repo.recordPayment(accountId, payeeIdentifier, a, Instant.now())
        assertThat(suppressed).isEmpty()

        repo.recordPayment(accountId, payeeIdentifier, a, Instant.now())

        assertThat(suppressed).containsExactly("payee_history")
    }
}
