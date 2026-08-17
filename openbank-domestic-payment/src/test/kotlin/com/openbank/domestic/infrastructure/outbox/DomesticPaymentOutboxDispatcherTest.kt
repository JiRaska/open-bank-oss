// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.outbox

import com.openbank.domestic.application.port.out.DomesticPaymentOutboxRepository
import com.openbank.domestic.infrastructure.kafka.KafkaDomesticPaymentEventPublisher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Stubs/verifies `claimProcessable` (#1201's atomic claim), not `listProcessable`: the shared
 * `OutboxDispatch.dispatchOnce` calls the former, and a `mockk()` never falls through to the
 * interface's default `claimProcessable = listProcessable(limit)` body — it intercepts every
 * call at the proxy level, so an un-stubbed `claimProcessable` throws (swallowed by
 * `dispatchOnce`'s `runCatching`), silently skipping the batch. See
 * `BillingOutboxDispatcherTest` for the full explanation.
 */
class DomesticPaymentOutboxDispatcherTest {

    private val outboxRepository: DomesticPaymentOutboxRepository = mockk()
    private val eventPublisher: KafkaDomesticPaymentEventPublisher = mockk()

    private fun dispatcher(): DomesticPaymentOutboxDispatcher =
        DomesticPaymentOutboxDispatcher(outboxRepository, eventPublisher, dispatchEnabled = true)

    private fun entry(eventId: UUID = UUID.randomUUID(), payload: String = "{\"event\":\"$eventId\"}") = OutboxEntry(
        eventId = eventId,
        aggregateId = UUID.randomUUID(),
        eventType = "domestic.payment.created",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T09:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `happy drain publishes each row and marks it sent`(): Unit = runBlocking {
        val first = entry()
        val second = entry()
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(first, second)
        coJustRun { eventPublisher.publish(any()) }
        coJustRun { outboxRepository.markSent(any(), any()) }

        dispatcher().dispatch()

        coVerify(exactly = 1) { eventPublisher.publish(first) }
        coVerify(exactly = 1) { eventPublisher.publish(second) }
        coVerify(exactly = 1) { outboxRepository.markSent(first.eventId, any()) }
        coVerify(exactly = 1) { outboxRepository.markSent(second.eventId, any()) }
    }

    @Test
    fun `publish failure marks the row failed and continues the batch`(): Unit = runBlocking {
        val failing = entry()
        val healthy = entry()
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(failing, healthy)
        coEvery { eventPublisher.publish(failing) } throws RuntimeException("kafka down")
        coJustRun { eventPublisher.publish(healthy) }
        coJustRun { outboxRepository.markSent(any(), any()) }
        coEvery { outboxRepository.markFailed(any(), any(), any()) } returns OutboxStatus.FAILED

        dispatcher().dispatch()

        coVerify(exactly = 1) { outboxRepository.markFailed(failing.eventId, "kafka down", any()) }
        coVerify(exactly = 0) { outboxRepository.markSent(failing.eventId, any()) }
        coVerify(exactly = 1) { outboxRepository.markSent(healthy.eventId, any()) }
    }

    @Test
    fun `a repository listing fault is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { outboxRepository.claimProcessable(any(), any()) } throws RuntimeException("db unreachable")

        dispatcher().dispatch()

        coVerify(exactly = 0) { outboxRepository.markSent(any(), any()) }
        coVerify(exactly = 0) { outboxRepository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `dispatch is skipped when dispatchEnabled is false`(): Unit = runBlocking {
        val disabled = DomesticPaymentOutboxDispatcher(outboxRepository, eventPublisher, dispatchEnabled = false)
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns emptyList()

        disabled.dispatch()

        coVerify(exactly = 0) { outboxRepository.claimProcessable(any(), any()) }
    }
}
