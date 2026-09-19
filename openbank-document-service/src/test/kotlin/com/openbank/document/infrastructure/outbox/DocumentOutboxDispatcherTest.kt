// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.outbox

import com.openbank.document.application.port.out.DocumentOutboxRepository
import com.openbank.libs.observability.DomainMetrics
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
 * `openbank.outbox.dispatch-enabled` defaults to FALSE fleet-wide, and a service that forgets to
 * set it never dispatches anything with no error and `attempt_count` stuck at 0. The switch is
 * therefore asserted in both positions — the disabled case must not even CLAIM rows, since a claim
 * transitions them to DISPATCHING.
 */
class DocumentOutboxDispatcherTest {

    private val repo = mockk<DocumentOutboxRepository>()
    private val publisher = mockk<OutboxEventPublisher>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)

    private val entry = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "DocumentGenerated",
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    private fun dispatcher(enabled: Boolean) = DocumentOutboxDispatcher(repo, publisher, enabled, metrics)

    @Test
    fun `with dispatch disabled nothing is claimed and nothing is published`(): Unit = runBlocking {
        dispatcher(enabled = false).dispatch()

        coVerify(exactly = 0) { repo.claimProcessable(any(), any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `with dispatch enabled a claimed row is published and marked sent`(): Unit = runBlocking {
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(entry)
        coEvery { publisher.publish(entry) } returns Unit
        coEvery { repo.markSent(entry.eventId, any()) } returns Unit

        dispatcher(enabled = true).dispatch()

        coVerify(exactly = 1) { publisher.publish(entry) }
        coVerify(exactly = 1) { repo.markSent(entry.eventId, any()) }
    }

    @Test
    fun `a publish failure marks the row FAILED and never marks it sent`(): Unit = runBlocking {
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(entry)
        coEvery { publisher.publish(entry) } throws IllegalStateException("broker rejected")
        coEvery { repo.markFailed(entry.eventId, "broker rejected", any()) } returns OutboxStatus.FAILED

        dispatcher(enabled = true).dispatch()

        coVerify(exactly = 1) { repo.markFailed(entry.eventId, "broker rejected", any()) }
        coVerify(exactly = 0) { repo.markSent(any(), any()) }
    }

    @Test
    fun `an empty claim is a no-op, not a publish of nothing`(): Unit = runBlocking {
        coEvery { repo.claimProcessable(any(), any()) } returns emptyList()

        dispatcher(enabled = true).dispatch()

        coVerify(exactly = 0) { publisher.publish(any()) }
    }
}
