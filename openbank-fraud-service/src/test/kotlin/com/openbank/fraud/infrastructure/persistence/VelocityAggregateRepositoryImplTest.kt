// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.domain.model.VelocityWindow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class VelocityAggregateRepositoryImplTest {

    private val pool = mockk<PgPool>()

    private val metrics = mockk<FraudMetricsPort>(relaxed = true)
    private val repository =
        VelocityAggregateRepositoryImpl(pool, Clock.systemUTC(), metrics, APPLIED_SIGNAL_WINDOW)

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
        // A non-zero rowCount means "the upsert applied" — the suppressed case is asserted against a
        // real database in VelocityAggregateRepositoryImplIT, which is the only place it is decidable.
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
    fun `recordTransaction executes upsert for all three windows`(): Unit = runBlocking {
        val rowSet = mockRowSet(emptyRowIterator())
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        repository.recordTransaction(
            UUID.randomUUID(),
            BigDecimal("100.00"),
            "CZK",
            UUID.randomUUID(),
            Instant.parse("2026-07-09T10:30:00Z"),
        )

        // recordTransaction calls upsert once per window (H1, H24, D7)
        verify(exactly = 3) { pool.preparedQuery(any<String>()) }
    }

    @Test
    fun `findAggregate returns null when no row exists`(): Unit = runBlocking {
        val rowSet = mockRowSet(emptyRowIterator())
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        val result = repository.findAggregate(UUID.randomUUID(), VelocityWindow.H1, "EUR")

        assertThat(result).isNull()
    }

    @Test
    fun `findAggregate maps row to VelocityAggregate`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val row = mockk<Row>()
        every { row.getLong(0) } returns 5L
        every { row.getBigDecimal(1) } returns BigDecimal("500.00")

        val rowSet = mockRowSet(rowIteratorOf(row))
        val pq = mockPreparedQuery(rowSet)
        every { pool.preparedQuery(any<String>()) } returns pq

        val result = repository.findAggregate(accountId, VelocityWindow.H24, "EUR")

        assertThat(result).isNotNull
        assertThat(result!!.transactionCount).isEqualTo(5L)
        assertThat(result.totalAmount).isEqualByComparingTo("500.00")
        assertThat(result.currency).isEqualTo("EUR")
        assertThat(result.window).isEqualTo(VelocityWindow.H24)
        assertThat(result.accountId).isEqualTo(accountId)
    }

    @Test
    fun `bucketStart H1 truncates to hour`() {
        val now = Instant.parse("2024-03-15T14:37:42Z")
        val start = VelocityWindow.H1.bucketStart(now)
        assertThat(start).isEqualTo(Instant.parse("2024-03-15T14:00:00Z"))
    }

    @Test
    fun `bucketStart H24 truncates to day`() {
        val now = Instant.parse("2024-03-15T14:37:42Z")
        val start = VelocityWindow.H24.bucketStart(now)
        assertThat(start).isEqualTo(Instant.parse("2024-03-15T00:00:00Z"))
    }

    @Test
    fun `bucketStart D7 returns start of 7-day epoch bucket`() {
        val now = Instant.parse("2024-03-15T14:37:42Z")
        val start = VelocityWindow.D7.bucketStart(now)
        // Must be a multiple of 7 days from epoch
        val epochDays = start.epochSecond / 86400
        assertThat(epochDays % 7).isEqualTo(0L)
        // Must be <= today
        assertThat(start).isBeforeOrEqualTo(now.truncatedTo(ChronoUnit.DAYS))
    }

    private companion object {
        const val APPLIED_SIGNAL_WINDOW = 100
    }
}
