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
}
