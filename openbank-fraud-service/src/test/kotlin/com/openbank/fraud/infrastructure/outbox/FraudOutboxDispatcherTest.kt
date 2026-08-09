// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.infrastructure.outbox

import com.openbank.fraud.application.port.out.FraudOutboxRepository
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

class FraudOutboxDispatcherTest {

    private val entry = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "fraud.hold_changed",
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `dispatch is a no-op when dispatch-enabled is false`() {
        val repo = mockk<FraudOutboxRepository>()
        val publisher = mockk<OutboxEventPublisher>()
        val dispatcher = FraudOutboxDispatcher(repo, publisher, dispatchEnabled = false)

        runBlocking { dispatcher.dispatch() }

        coVerify(exactly = 0) { repo.claimProcessable(any(), any()) }
    }

    @Test
    fun `dispatch claims and publishes when enabled`() {
        val repo = mockk<FraudOutboxRepository>()
        val publisher = mockk<OutboxEventPublisher>()
        coEvery { repo.claimProcessable(any(), any()) } returns listOf(entry) andThen emptyList()
        coEvery { publisher.publish(entry) } returns Unit
        coEvery { repo.markSent(entry.eventId, any()) } returns Unit
        val dispatcher = FraudOutboxDispatcher(repo, publisher, dispatchEnabled = true)

        runBlocking { dispatcher.dispatch() }

        coVerify { publisher.publish(entry) }
        coVerify { repo.markSent(entry.eventId, any()) }
    }

    @Test
    fun `a claim failure never propagates past the scheduled tick`() {
        val repo = mockk<FraudOutboxRepository>()
        val publisher = mockk<OutboxEventPublisher>()
        coEvery { repo.claimProcessable(any(), any()) } throws IllegalStateException("db down")
        val dispatcher = FraudOutboxDispatcher(repo, publisher, dispatchEnabled = true)

        // OutboxDispatch.dispatchOnce catches claimProcessable failures internally (libs-runtime) —
        // this asserts that contract holds through the dispatch() gate too: no propagation.
        runBlocking { dispatcher.dispatch() }

        coVerify { repo.claimProcessable(any(), any()) }
    }
}
