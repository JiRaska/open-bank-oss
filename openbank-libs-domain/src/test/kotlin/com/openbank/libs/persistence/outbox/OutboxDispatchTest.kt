// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OutboxDispatchTest {

    /** Minimal in-memory repository recording the dispatcher's bookkeeping calls. */
    private class FakeRepo(private val rows: List<OutboxEntry>) : OutboxRepository {
        val sent = mutableListOf<UUID>()
        val failed = mutableListOf<Pair<UUID, String>>()
        override suspend fun listProcessable(limit: Int): List<OutboxEntry> = rows.take(limit)
        override suspend fun markSent(eventId: UUID, sentAt: Instant) {
            sent += eventId
        }
        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
            failed += eventId to error
        }
    }

    private fun entry(type: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = type,
        payload = """{"t":"$type"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `publishes each processable row and marks it sent, passing the full entry`() {
        val rows = listOf(entry("a.created"), entry("b.created"))
        val repo = FakeRepo(rows)
        val published = mutableListOf<OutboxEntry>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e -> published += e }
        }

        // the dispatcher hands the whole entry (not just the payload) to the publisher
        assertThat(published).containsExactlyElementsOf(rows)
        assertThat(repo.sent).containsExactly(rows[0].eventId, rows[1].eventId)
        assertThat(repo.failed).isEmpty()
    }

    @Test
    fun `marks a row failed when its publish throws, and does not mark it sent`() {
        val row = entry("boom")
        val repo = FakeRepo(listOf(row))

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { throw IllegalStateException("kafka down") }
        }

        assertThat(repo.sent).isEmpty()
        assertThat(repo.failed).hasSize(1)
        assertThat(repo.failed.single().first).isEqualTo(row.eventId)
        assertThat(repo.failed.single().second).isEqualTo("kafka down")
    }

    private fun breakerOpen() =
        org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException("circuit breaker is open")

    @Test
    fun `an open breaker consumes no attempt and abandons the rest of the batch`() {
        val rows = listOf(entry("a.created"), entry("b.created"), entry("c.created"))
        val repo = FakeRepo(rows)
        val attempted = mutableListOf<UUID>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e ->
                attempted += e.eventId
                throw breakerOpen()
            }
        }

        // The first row is the only one offered; the batch stops there rather than burning an
        // attempt on every remaining row (#4005: 24 rows x 10 ticks -> all DEAD in ~50 s).
        assertThat(attempted).containsExactly(rows[0].eventId)
        assertThat(repo.failed).isEmpty()
        assertThat(repo.sent).isEmpty()
    }

    @Test
    fun `a breaker-open cause nested under another exception is still not an attempt`() {
        val row = entry("nested")
        val repo = FakeRepo(listOf(row))

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) {
                throw IllegalStateException("wrapped", breakerOpen())
            }
        }

        assertThat(repo.failed).isEmpty()
        assertThat(repo.sent).isEmpty()
    }

    @Test
    fun `a real publish failure still counts, so a poison row still reaches DEAD`() {
        val rows = listOf(entry("poison"), entry("healthy"))
        val repo = FakeRepo(rows)
        val attempted = mutableListOf<UUID>()

        runBlocking {
            OutboxDispatch.dispatchOnce(repo) { e ->
                attempted += e.eventId
                if (e.eventType == "poison") throw IllegalStateException("serialization failed")
            }
        }

        // Unlike a breaker fast-fail, a genuine failure is recorded AND the batch continues.
        assertThat(attempted).containsExactly(rows[0].eventId, rows[1].eventId)
        assertThat(repo.failed.map { it.first }).containsExactly(rows[0].eventId)
        assertThat(repo.sent).containsExactly(rows[1].eventId)
    }

    @Test
    fun `a timeout is NOT treated as transport-unavailable`() {
        // A @Timeout can fire after the record already reached the broker, so it must keep
        // counting as a real attempt — otherwise the row is retried and the consumer sees a
        // duplicate. Deliberately excluded from TRANSPORT_UNAVAILABLE_EXCEPTIONS.
        assertThat(
            OutboxDispatch.isTransportUnavailable(
                java.util.concurrent.TimeoutException("publish timed out"),
            ),
        ).isFalse()
        assertThat(OutboxDispatch.isTransportUnavailable(breakerOpen())).isTrue()
        assertThat(OutboxDispatch.isTransportUnavailable(null)).isFalse()
    }
}
