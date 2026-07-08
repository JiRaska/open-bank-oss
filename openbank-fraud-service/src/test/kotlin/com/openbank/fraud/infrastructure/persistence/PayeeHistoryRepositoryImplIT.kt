// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
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
}
