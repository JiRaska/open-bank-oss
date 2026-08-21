// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.PreparedQuery
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.RowIterator
import io.vertx.mutiny.sqlclient.RowSet
import io.vertx.mutiny.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Mocked-`PgPool` unit test (mirrors [VelocityAggregateRepositoryImplTest]'s style) — asserts the
 * repository issues the expected query shape and maps rows correctly. The real upsert/idempotency
 * SQL semantics are verified against a live Postgres in [PayeeHistoryRepositoryImplIT].
 */
class PayeeHistoryRepositoryImplTest {

    private val pool = mockk<PgPool>()

    private val metrics = mockk<FraudMetricsPort>(relaxed = true)
    private val repository = PayeeHistoryRepositoryImpl(pool, metrics, APPLIED_SIGNAL_WINDOW)

    private fun emptyRowIterator(): RowIterator<Row> {
        val iter = mockk<RowIterator<Row>>()
        every { iter.hasNext() } returns false
        return iter
    }

    private fun rowIteratorOf(vararg rows: Row): RowIterator<Row> {
        val list = rows.toList()
        val iter = mockk<RowIterator<Row>>()
        val calls = list.iterator()
        every { iter.hasNext() } answers { calls.hasNext() }
        every { iter.next() } answers { calls.next() }
        return iter
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockRowSet(iter: RowIterator<Row>): RowSet<Row> {
        val rowSet = mockk<RowSet<Row>>()
        every { rowSet.iterator() } returns iter
        // Non-zero rowCount = "the upsert applied". The suppressed (rowCount 0) case is only
        // decidable against a real database — see PayeeHistoryRepositoryImplIT.
        every { rowSet.rowCount() } returns 1
        return rowSet
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockPreparedQuery(rowSet: RowSet<Row>): PreparedQuery<RowSet<Row>> {
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val tupleSlot = slot<Tuple>()
        every { pq.execute(capture(tupleSlot)) } returns Uni.createFrom().item(rowSet)
        return pq
    }

    @Test
    fun `recordPayment executes exactly one upsert query`(): Unit = runBlocking {
        val rowSet = mockRowSet(emptyRowIterator())
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        repository.recordPayment(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(), Instant.now())

        io.mockk.verify(exactly = 1) { pool.preparedQuery(any<String>()) }
    }

    @Test
    fun `findHistory returns null when no row exists`(): Unit = runBlocking {
        val rowSet = mockRowSet(emptyRowIterator())
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        val result = repository.findHistory(UUID.randomUUID(), UUID.randomUUID().toString())

        assertThat(result).isNull()
    }

    @Test
    fun `findHistory maps row to PayeeHistory`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payeeIdentifier = UUID.randomUUID().toString()
        val firstSeen = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val lastPaid = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val row = mockk<Row>()
        every { row.getOffsetDateTime(0) } returns firstSeen
        every { row.getOffsetDateTime(1) } returns lastPaid
        every { row.getLong(2) } returns 4L

        val rowSet = mockRowSet(rowIteratorOf(row))
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        val result = repository.findHistory(accountId, payeeIdentifier)

        assertThat(result).isNotNull
        assertThat(result!!.accountId).isEqualTo(accountId)
        assertThat(result.payeeIdentifier).isEqualTo(payeeIdentifier)
        assertThat(result.firstSeenAt).isEqualTo(firstSeen.toInstant())
        assertThat(result.lastPaidAt).isEqualTo(lastPaid.toInstant())
        assertThat(result.paymentCount).isEqualTo(4L)
    }

    private companion object {
        const val APPLIED_SIGNAL_WINDOW = 100
    }
}
