// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.outbox

import com.openbank.domestic.application.port.out.DomesticPaymentOutboxRepository
import com.openbank.domestic.infrastructure.kafka.KafkaDomesticPaymentEventPublisher
import com.openbank.libs.persistence.outbox.OutboxClaim
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DomesticPaymentOutboxDispatcherTest {

    private val outboxRepository: DomesticPaymentOutboxRepository = mockk()
    private val eventPublisher: KafkaDomesticPaymentEventPublisher = mockk()

    private fun dispatcher(): DomesticPaymentOutboxDispatcher = DomesticPaymentOutboxDispatcher(
        outboxRepository,
        eventPublisher,
        dispatchEnabled = true,
        metrics = mockk(relaxed = true),
        dispatchPollInterval = Duration.ofSeconds(5),
    )

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
    fun `happy drain publishes each row and acknowledges its claim`(): Unit = runBlocking {
        val first = OutboxClaim(entry(), UUID.randomUUID())
        val second = OutboxClaim(entry(), UUID.randomUUID())
        coEvery { outboxRepository.claimWithTokens(any(), any()) } returnsMany
            listOf(listOf(first, second), emptyList())
        coJustRun { eventPublisher.publish(any()) }
        coEvery { outboxRepository.markClaimsSent(any(), any()) } returns
            setOf(first.entry.eventId, second.entry.eventId)

        dispatcher().dispatch()

        coVerify(exactly = 1) { eventPublisher.publish(first.entry) }
        coVerify(exactly = 1) { eventPublisher.publish(second.entry) }
        coVerify(exactly = 1) { outboxRepository.markClaimsSent(listOf(first, second), any()) }
    }

    @Test
    fun `publish failure marks the current claim failed and continues the batch`(): Unit = runBlocking {
        val failing = OutboxClaim(entry(), UUID.randomUUID())
        val healthy = OutboxClaim(entry(), UUID.randomUUID())
        coEvery { outboxRepository.claimWithTokens(any(), any()) } returnsMany
            listOf(listOf(failing, healthy), emptyList())
        coEvery { eventPublisher.publish(failing.entry) } throws RuntimeException("kafka down")
        coJustRun { eventPublisher.publish(healthy.entry) }
        coEvery { outboxRepository.markClaimsSent(listOf(healthy), any()) } returns setOf(healthy.entry.eventId)
        coEvery { outboxRepository.markClaimFailed(failing, "kafka down", any()) } returns OutboxStatus.FAILED

        dispatcher().dispatch()

        coVerify(exactly = 1) { outboxRepository.markClaimFailed(failing, "kafka down", any()) }
        coVerify(exactly = 1) { outboxRepository.markClaimsSent(listOf(healthy), any()) }
    }

    @Test
    fun `a repository claim fault is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { outboxRepository.claimWithTokens(any(), any()) } throws RuntimeException("db unreachable")

        dispatcher().dispatch()

        coVerify(exactly = 0) { outboxRepository.markClaimsSent(any(), any()) }
        coVerify(exactly = 0) { outboxRepository.markClaimFailed(any(), any(), any()) }
    }

    @Test
    fun `dispatch is skipped when dispatchEnabled is false`(): Unit = runBlocking {
        val disabled = DomesticPaymentOutboxDispatcher(
            outboxRepository,
            eventPublisher,
            dispatchEnabled = false,
            metrics = mockk(relaxed = true),
            dispatchPollInterval = Duration.ofSeconds(5),
        )
        coEvery { outboxRepository.claimWithTokens(any(), any()) } returns emptyList()

        disabled.dispatch()

        coVerify(exactly = 0) { outboxRepository.claimWithTokens(any(), any()) }
    }
}
