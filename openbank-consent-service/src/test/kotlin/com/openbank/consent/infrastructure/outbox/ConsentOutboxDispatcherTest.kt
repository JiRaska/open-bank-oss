// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.outbox

import com.openbank.consent.application.port.out.ConsentOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Stubs/verifies `claimProcessable` (#1201's atomic claim), not `listProcessable`: the shared
 * `OutboxDispatch.dispatchOnce` calls the former, and a plain `mockk()` never falls through to
 * the interface's default `claimProcessable = listProcessable(limit)` body — it intercepts every
 * call at the proxy level, so an un-stubbed `claimProcessable` on a relaxed mock silently returns
 * an empty list instead. See `BillingOutboxDispatcherTest` for the full explanation.
 */
class ConsentOutboxDispatcherTest {

    private val outboxRepository = mockk<ConsentOutboxRepository>(relaxed = true)
    private val eventPublisher = mockk<OutboxEventPublisher>(relaxed = true)

    private fun dispatcher(enabled: Boolean = true): ConsentOutboxDispatcher = ConsentOutboxDispatcher(
        outboxRepository,
        eventPublisher,
        dispatchEnabled = enabled,
        metrics = mockk(relaxed = true),
    )

    @Test
    fun `dispatch publishes and marks each processable row sent`(): Unit = runBlocking {
        val entry1 = entry("payload-1")
        val entry2 = entry("payload-2")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(entry1, entry2)

        dispatcher().dispatch()

        coVerify(exactly = 1) { eventPublisher.publish(entry1) }
        coVerify(exactly = 1) { eventPublisher.publish(entry2) }
        coVerify(exactly = 1) { outboxRepository.markSent(entry1.eventId, any()) }
        coVerify(exactly = 1) { outboxRepository.markSent(entry2.eventId, any()) }
        coVerify(exactly = 0) { outboxRepository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `dispatch does nothing when disabled`(): Unit = runBlocking {
        dispatcher(enabled = false).dispatch()

        coVerify(exactly = 0) { outboxRepository.claimProcessable(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `dispatch marks a row failed when publish throws`(): Unit = runBlocking {
        val entry = entry("payload-x")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(entry)
        coEvery { eventPublisher.publish(entry) } throws RuntimeException("kafka down")

        dispatcher().dispatch()

        coVerify(exactly = 1) { outboxRepository.markFailed(entry.eventId, "kafka down", any()) }
        coVerify(exactly = 0) { outboxRepository.markSent(any(), any()) }
    }

    @Test
    fun `dispatch swallows a repository failure so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { outboxRepository.claimProcessable(any(), any()) } throws RuntimeException("db unavailable")

        // Must not propagate.
        dispatcher().dispatch()

        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    private fun entry(payload: String): OutboxEntry {
        val now = Instant.now()
        return OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "ConsentGranted",
            payload = payload,
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = now,
            updatedAt = now,
            sentAt = null,
            lastError = null,
        )
    }
}
