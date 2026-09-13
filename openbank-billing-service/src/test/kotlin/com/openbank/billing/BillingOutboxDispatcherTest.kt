// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.infrastructure.outbox.BillingOutboxDispatcher
import com.openbank.billing.infrastructure.outbox.LedgerOutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxRepository
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
 * The scheduled dispatch tick is gated on `openbank.outbox.dispatch-enabled` (default **false** —
 * the classic silent-outbox footgun) and, when enabled, must mark each row SENT on success and
 * FAILED on a publish error so the row is retried rather than lost. Mirrors
 * `openbank-lending-service`'s `LendingOutboxDispatcherTest`.
 *
 * Stubs/verifies `claimProcessable`, not `listProcessable`: `OutboxDispatch.dispatchOnce` (which
 * the shared `AbstractOutboxDispatcher.dispatchScheduledBatch` delegates to) claims rows via
 * `OutboxRepository.claimProcessable` (#1201, atomic claim so two dispatcher instances during an
 * Argo Rollouts canary window can't both grab the same row). `claimProcessable`'s default body
 * calls `listProcessable`, but that default only resolves for a REAL class implementing the
 * interface — a `mockk()` intercepts every call at the proxy level regardless of default method
 * bodies, so an un-stubbed `claimProcessable` throws (swallowed by `dispatchOnce`'s
 * `runCatching`), silently skipping the whole batch. Stubbing `listProcessable` alone verified
 * nothing.
 */
class BillingOutboxDispatcherTest {

    private val repo = mockk<OutboxRepository>()
    private val publisher = mockk<LedgerOutboxEventPublisher>()

    private fun entry(eventId: UUID = UUID.randomUUID()) = OutboxEntry(
        eventId = eventId,
        aggregateId = UUID.randomUUID(),
        eventType = "billing.fee.post-intent.v1",
        payload = """{"idempotencyKey":"fee-x"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `dispatch is a no-op while dispatch-enabled is false`(): Unit = runBlocking {
        val dispatcher = BillingOutboxDispatcher(
            repo,
            publisher,
            dispatchEnabled = false,
            metrics = mockk(relaxed = true),
        )

        dispatcher.dispatch()

        coVerify(exactly = 0) { repo.claimProcessable(any(), any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `an enabled dispatch publishes each processable row and marks it sent`(): Unit = runBlocking {
        val first = entry()
        val second = entry()
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(first, second)
        coJustRun { publisher.publish(any()) }
        coJustRun { repo.markSent(any(), any()) }

        BillingOutboxDispatcher(repo, publisher, dispatchEnabled = true, metrics = mockk(relaxed = true)).dispatch()

        coVerify(exactly = 1) { publisher.publish(first) }
        coVerify(exactly = 1) { publisher.publish(second) }
        coVerify(exactly = 1) { repo.markSent(first.eventId, any()) }
        coVerify(exactly = 1) { repo.markSent(second.eventId, any()) }
        coVerify(exactly = 0) { repo.markFailed(any(), any(), any()) }
    }

    @Test
    fun `a failed publish marks the row failed for retry instead of dropping it`(): Unit = runBlocking {
        val poisoned = entry()
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(poisoned)
        coEvery { publisher.publish(poisoned) } throws IllegalStateException("ledger unavailable")
        coEvery { repo.markFailed(any(), any(), any()) } returns OutboxStatus.FAILED

        BillingOutboxDispatcher(repo, publisher, dispatchEnabled = true, metrics = mockk(relaxed = true)).dispatch()

        coVerify(exactly = 1) { repo.markFailed(poisoned.eventId, "ledger unavailable", any()) }
        coVerify(exactly = 0) { repo.markSent(any(), any()) }
    }

    @Test
    fun `dispatchScheduledBatch drives the same dispatch loop without the resilience annotations`(): Unit =
        runBlocking {
            val entryRow = entry()
            coEvery { repo.claimProcessable(any(), any()) } returns listOf(entryRow)
            coJustRun { publisher.publish(any()) }
            coJustRun { repo.markSent(any(), any()) }

            BillingOutboxDispatcher(
                repo,
                publisher,
                dispatchEnabled = true,
                metrics = mockk(relaxed = true),
            ).dispatchScheduledBatch()

            coVerify(exactly = 1) { publisher.publish(entryRow) }
            coVerify(exactly = 1) { repo.markSent(entryRow.eventId, any()) }
        }
}
