// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.sepainstant.application.port.out.SctInstOutboxEntry
import com.openbank.sepainstant.application.port.out.SctInstOutboxStatus
import com.openbank.sepainstant.infrastructure.persistence.repository.SctInstOutboxRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * Unit tests for the scheduled outbox drain: a delivered payload is marked SENT, a transport
 * failure is marked FAILED (with the exception message) and must not abort the rest of the batch.
 */
class SctInstOutboxDispatcherTest {

    private val outboxRepository = mockk<SctInstOutboxRepositoryImpl>()
    private val emitter = mockk<MutinyEmitter<String>>()
    private val dispatcher = SctInstOutboxDispatcher(outboxRepository, emitter)

    @BeforeEach
    fun passPanacheSessionThrough() {
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Any>>>()) } answers { firstArg<Supplier<Uni<Any>>>().get() }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Panache::class)
    }

    private fun entry(eventId: UUID, payload: String) = SctInstOutboxEntry(
        eventId = eventId,
        aggregateId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
        eventType = "SctInstPaymentSettled",
        payload = payload,
        status = SctInstOutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-01-01T12:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T12:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `a delivered payload is marked SENT`() {
        val eventId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        every { outboxRepository.listProcessableUni(any()) } returns
            Uni.createFrom().item(listOf(entry(eventId, "payload-1")))
        every { emitter.send("payload-1") } returns Uni.createFrom().voidItem()
        every { outboxRepository.markSentUni(eventId) } returns Uni.createFrom().voidItem()

        dispatcher.dispatchScheduledBatch().await().indefinitely()

        verify(exactly = 1) { outboxRepository.markSentUni(eventId) }
        verify(exactly = 0) { outboxRepository.markFailedUni(any(), any()) }
    }

    @Test
    fun `a failed send marks the row FAILED and still drains the rest of the batch`() {
        val failing = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val healthy = UUID.fromString("22222222-2222-2222-2222-222222222222")
        every { outboxRepository.listProcessableUni(any()) } returns
            Uni.createFrom().item(listOf(entry(failing, "bad"), entry(healthy, "good")))
        every { emitter.send("bad") } returns Uni.createFrom().failure(RuntimeException("broker down"))
        every { emitter.send("good") } returns Uni.createFrom().voidItem()
        val errors = mutableListOf<String>()
        every { outboxRepository.markFailedUni(failing, capture(errors)) } returns Uni.createFrom().voidItem()
        every { outboxRepository.markSentUni(healthy) } returns Uni.createFrom().voidItem()

        dispatcher.dispatchScheduledBatch().await().indefinitely()

        assertThat(errors).containsExactly("broker down")
        verify(exactly = 1) { outboxRepository.markFailedUni(failing, "broker down") }
        verify(exactly = 1) { outboxRepository.markSentUni(healthy) }
    }

    @Test
    fun `a failure without a message falls back to the exception class name`() {
        val eventId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        every { outboxRepository.listProcessableUni(any()) } returns
            Uni.createFrom().item(listOf(entry(eventId, "bad")))
        every { emitter.send("bad") } returns Uni.createFrom().failure(IllegalStateException())
        val errors = mutableListOf<String>()
        every { outboxRepository.markFailedUni(eventId, capture(errors)) } returns Uni.createFrom().voidItem()

        dispatcher.dispatchScheduledBatch().await().indefinitely()

        assertThat(errors).containsExactly("IllegalStateException")
    }
}
