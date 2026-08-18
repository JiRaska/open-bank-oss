// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.swift.application.port.out.SwiftOutboxRepository
import com.openbank.swift.infrastructure.kafka.KafkaSwiftOutboxEventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Stubs/verifies `claimProcessable` (#1201's atomic claim), not `listProcessable`: a relaxed
 * `mockk()` never falls through to the interface's default `claimProcessable = listProcessable`
 * body, so an un-stubbed `claimProcessable` silently returns an empty list instead. See
 * `BillingOutboxDispatcherTest` for the full explanation.
 */
class SwiftOutboxDispatcherTest {

    private val outboxRepository = mockk<SwiftOutboxRepository>(relaxed = true)
    private val eventPublisher = mockk<KafkaSwiftOutboxEventPublisher>(relaxed = true)
    private val dispatcher = SwiftOutboxDispatcher(
        outboxRepository,
        eventPublisher,
        dispatchEnabled = true,
        metrics = mockk(relaxed = true),
    )

    @Test
    fun `dispatch publishes each processable row and marks it sent`(): Unit = runBlocking {
        val a = entry(payload = "evt-a")
        val b = entry(payload = "evt-b")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(a, b)

        dispatcher.dispatch()

        coVerify(exactly = 1) { eventPublisher.publish(a) }
        coVerify(exactly = 1) { eventPublisher.publish(b) }
        // markSent's `sentAt` defaults to Instant.now() at the call site → match it with any().
        coVerify(exactly = 1) { outboxRepository.markSent(a.eventId, any()) }
        coVerify(exactly = 1) { outboxRepository.markSent(b.eventId, any()) }
        coVerify(exactly = 0) { outboxRepository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `a publish failure marks that row failed with the error and does not mark it sent`(): Unit = runBlocking {
        val failing = entry(payload = "boom")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(failing)
        coEvery { eventPublisher.publish(failing) } throws RuntimeException("kafka down")

        dispatcher.dispatch()

        coVerify(exactly = 1) { outboxRepository.markFailed(failing.eventId, "kafka down", any()) }
        coVerify(exactly = 0) { outboxRepository.markSent(failing.eventId, any()) }
    }

    @Test
    fun `a repository read failure is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { outboxRepository.claimProcessable(any(), any()) } throws IllegalStateException("db unavailable")

        // Must not throw.
        dispatcher.dispatch()

        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `dispatch is a no-op when dispatch-enabled is false`(): Unit = runBlocking {
        val disabledDispatcher = SwiftOutboxDispatcher(
            outboxRepository,
            eventPublisher,
            dispatchEnabled = false,
            metrics = mockk(relaxed = true),
        )

        disabledDispatcher.dispatch()

        coVerify(exactly = 0) { outboxRepository.claimProcessable(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    private fun entry(payload: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "SwiftMessageValidated",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
        sentAt = null,
        lastError = null,
    )
}
