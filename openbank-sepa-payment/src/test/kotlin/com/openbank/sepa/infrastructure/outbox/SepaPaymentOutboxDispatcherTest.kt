// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sepa.application.port.out.SepaPaymentOutboxRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Stubs/verifies `claimProcessable` (#1201's atomic claim), not `listProcessable`: a `mockk()`
 * never falls through to the interface's default `claimProcessable = listProcessable(limit)`
 * body — it intercepts every call at the proxy level — so an un-stubbed `claimProcessable` throws
 * (swallowed by `dispatchOnce`'s `runCatching`), silently skipping the whole batch. See
 * `BillingOutboxDispatcherTest` for the full explanation.
 */
class SepaPaymentOutboxDispatcherTest {

    private lateinit var outboxRepository: SepaPaymentOutboxRepository
    private lateinit var eventPublisher: OutboxEventPublisher
    private lateinit var dispatcher: SepaPaymentOutboxDispatcher

    @BeforeEach
    fun setUp() {
        outboxRepository = mockk()
        eventPublisher = mockk()
        dispatcher = SepaPaymentOutboxDispatcher(
            outboxRepository,
            eventPublisher,
            dispatchEnabled = true,
            metrics = mockk(relaxed = true),
        )
    }

    private fun entry(eventId: UUID = UUID.randomUUID(), payload: String = "{\"e\":1}") = OutboxEntry(
        eventId = eventId,
        aggregateId = UUID.randomUUID(),
        eventType = "sepa.payment.created",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `happy drain publishes each entry and marks every row sent`(): Unit = runBlocking {
        val first = entry(payload = "{\"e\":\"a\"}")
        val second = entry(payload = "{\"e\":\"b\"}")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(first, second)
        coJustRun { eventPublisher.publish(any()) }
        coJustRun { outboxRepository.markSent(any(), any()) }

        dispatcher.dispatch()

        coVerify { eventPublisher.publish(first) }
        coVerify { eventPublisher.publish(second) }
        coVerify { outboxRepository.markSent(first.eventId, any()) }
        coVerify { outboxRepository.markSent(second.eventId, any()) }
        coVerify(exactly = 0) { outboxRepository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `publish failure marks the row failed with the error message and continues`(): Unit = runBlocking {
        val failing = entry(payload = "{\"e\":\"boom\"}")
        val ok = entry(payload = "{\"e\":\"ok\"}")
        coEvery { outboxRepository.claimProcessable(any(), any()) } returns listOf(failing, ok)
        coEvery { eventPublisher.publish(failing) } throws RuntimeException("broker down")
        coJustRun { eventPublisher.publish(ok) }
        coJustRun { outboxRepository.markSent(any(), any()) }
        coEvery { outboxRepository.markFailed(any(), any(), any()) } returns OutboxStatus.FAILED

        dispatcher.dispatch()

        coVerify { outboxRepository.markFailed(failing.eventId, "broker down", any()) }
        coVerify(exactly = 0) { outboxRepository.markSent(failing.eventId, any()) }
        // the loop keeps going: the next row is still published and marked sent
        coVerify { outboxRepository.markSent(ok.eventId, any()) }
    }

    @Test
    fun `repository listing failure is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { outboxRepository.claimProcessable(any(), any()) } throws RuntimeException("db unreachable")

        dispatcher.dispatch()

        coVerify(exactly = 0) { eventPublisher.publish(any()) }
        coVerify(exactly = 0) { outboxRepository.markSent(any(), any()) }
        coVerify(exactly = 0) { outboxRepository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `dispatch is a no-op when dispatch-enabled is false`(): Unit = runBlocking {
        val disabledDispatcher = SepaPaymentOutboxDispatcher(
            outboxRepository,
            eventPublisher,
            dispatchEnabled = false,
            metrics = mockk(relaxed = true),
        )

        disabledDispatcher.dispatch()

        coVerify(exactly = 0) { outboxRepository.claimProcessable(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }
}
