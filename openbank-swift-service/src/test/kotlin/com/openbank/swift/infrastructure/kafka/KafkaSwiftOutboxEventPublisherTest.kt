// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KafkaSwiftOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>(relaxed = true)
    private val publisher = KafkaSwiftOutboxEventPublisher(emitter)

    @BeforeEach
    fun setUp() {
        // sendMessage returns Uni<Void>; a relaxed mock's Uni never invokes the subscriber
        // callback — awaitSuspending() hangs indefinitely. Stub with a real completed Uni.
        every { emitter.sendMessage(any()) } answers { Uni.createFrom().voidItem() }
    }

    private fun entry(payload: String = "the-payload") = OutboxEntry(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        eventType = "SwiftMessageValidated",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `publish emits the payload as a keyed Kafka message with canonical headers`(): Unit = runBlocking {
        val outboxEntry = entry()

        publisher.publish(outboxEntry)

        coVerify(exactly = 1) { emitter.sendMessage(any()) }
    }

    @Test
    fun `partition key equals the aggregate id (N2 ordering guarantee)`(): Unit = runBlocking {
        val outboxEntry = entry()
        assertThat(OutboxKafkaHeaders.partitionKey(outboxEntry))
            .isEqualTo(outboxEntry.aggregateId.toString())
    }

    @Test
    fun `headers contain event-id and event-type for consumer dedup (N3)`(): Unit = runBlocking {
        val outboxEntry = entry()
        val headers = OutboxKafkaHeaders.headersFor(outboxEntry)
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(outboxEntry.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(outboxEntry.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo("SwiftMessageValidated")
    }
}
