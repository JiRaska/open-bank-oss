// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.psd2.application.port.out.Psd2OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `openbank.outbox.dispatch-enabled` defaults to `false` (CLAUDE.md pitfall) — verifies the
 * scheduled tick is a no-op gate around [com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher]'s
 * shared drain loop, and that a processable entry is published and marked sent through that loop
 * (which internally calls the protected `publishWithResilience` override).
 *
 * Stubs/verifies `claimProcessable` (#1201's atomic claim), not `listProcessable`: the shared
 * `OutboxDispatch.dispatchOnce` calls the former, and a `mockk()` never falls through to the
 * interface's default `claimProcessable = listProcessable(limit)` body — it intercepts every
 * call at the proxy level, so an un-stubbed `claimProcessable` throws (swallowed by
 * `dispatchOnce`'s `runCatching`), silently skipping the whole batch.
 */
class Psd2OutboxDispatcherTest {

    private val repo = mockk<Psd2OutboxRepository>()
    private val publisher = mockk<OutboxEventPublisher>()

    private fun sampleEntry() = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "psd2.consent.created",
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `dispatch is a no-op when dispatch-enabled is false`(): Unit = runBlocking {
        val dispatcher = Psd2OutboxDispatcher(repo, publisher, dispatchEnabled = false)

        dispatcher.dispatch()

        coVerify(exactly = 0) { repo.claimProcessable(any(), any()) }
    }

    @Test
    fun `dispatch drains the outbox when dispatch-enabled is true`(): Unit = runBlocking {
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(sampleEntry())
        coEvery { publisher.publish(any()) } returns Unit
        coEvery { repo.markSent(any(), any()) } returns Unit

        val dispatcher = Psd2OutboxDispatcher(repo, publisher, dispatchEnabled = true)

        dispatcher.dispatch()

        coVerify(exactly = 1) { repo.claimProcessable(any(), any()) }
        coVerify(exactly = 1) { publisher.publish(any()) }
        coVerify(exactly = 1) { repo.markSent(any(), any()) }
    }

    @Test
    fun `dispatch marks an entry failed when the publisher throws`(): Unit = runBlocking {
        val entry = sampleEntry()
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(entry)
        coEvery { publisher.publish(entry) } throws RuntimeException("kafka unavailable")
        coEvery { repo.markFailed(any(), any(), any()) } returns OutboxStatus.FAILED

        val dispatcher = Psd2OutboxDispatcher(repo, publisher, dispatchEnabled = true)

        dispatcher.dispatch()

        coVerify(exactly = 1) { repo.markFailed(entry.eventId, "kafka unavailable", any()) }
        coVerify(exactly = 0) { repo.markSent(any(), any()) }
    }
}
