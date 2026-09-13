// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Contract of the [OutboxRepository.countProcessable] default (ADR-0077 / ADR-0079). The default
 * materialises [OutboxRepository.listProcessable]; concrete repositories override it with a
 * `SELECT count(*)`, but the default must stay correct so a service that has not overridden it
 * still reports an accurate backlog.
 */
class OutboxCountProcessableTest {

    /** Records the limit the default passes to [listProcessable] so we can assert it asks for all rows. */
    private class FakeRepo(private val rows: List<OutboxEntry>) : OutboxRepository {
        var lastLimit: Int? = null
        override suspend fun listProcessable(limit: Int): List<OutboxEntry> {
            lastLimit = limit
            return rows.take(limit)
        }
        override suspend fun markSent(eventId: UUID, sentAt: Instant) = Unit
        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) = OutboxStatus.FAILED
    }

    private fun entry() = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "x.created",
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `default counts every processable row`() {
        val repo = FakeRepo(List(3) { entry() })

        val count: Long = runBlocking { repo.countProcessable() }

        assertThat(count).isEqualTo(3L)
    }

    @Test
    fun `default returns zero on an empty outbox`() {
        val repo = FakeRepo(emptyList())

        val count: Long = runBlocking { repo.countProcessable() }

        assertThat(count).isZero()
    }

    @Test
    fun `default asks listProcessable for the whole backlog, not a truncated page`() {
        val repo = FakeRepo(List(5) { entry() })

        runBlocking { repo.countProcessable() }

        // A capped limit would silently under-count a large backlog; the default must request all.
        assertThat(repo.lastLimit).isEqualTo(Int.MAX_VALUE)
    }
}
