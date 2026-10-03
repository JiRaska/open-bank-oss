// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0003 N2/N3 addressing on the wire: the record this publisher hands the connector must be
 * keyed by the AGGREGATE id (so one notification's events keep their order across a partition
 * rebalance) and must carry the event id as a consumer-visible idempotency key.
 *
 * Asserted off the emitted [Message]'s own metadata rather than off [OutboxKafkaHeaders] — the
 * question is whether this adapter actually applies the canonical shape, not whether the shared
 * helper computes it.
 */
class KafkaNotificationOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaNotificationOutboxEventPublisher(emitter)

    private val eventId: UUID = UUID.randomUUID()
    private val aggregateId: UUID = UUID.randomUUID()

    private fun entry(synthetic: Boolean = false) = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = "NotificationOutcome",
        payload = """{"outcome":"SENT"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-09-05T10:00:00Z"),
        updatedAt = Instant.parse("2026-09-05T10:00:00Z"),
        sentAt = null,
        lastError = null,
        synthetic = synthetic,
    )

    private fun capturePublished(entry: OutboxEntry): Message<String> {
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry) }
        return captured.captured
    }

    private fun metadata(message: Message<String>): OutgoingKafkaRecordMetadata<*> =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

    @Test
    fun `the record is keyed by the aggregate id, not the event id`() {
        val meta = metadata(capturePublished(entry()))

        assertThat(meta.key).isEqualTo(aggregateId.toString())
        assertThat(meta.key).isNotEqualTo(eventId.toString())
    }

    @Test
    fun `the payload is forwarded verbatim with the id and type headers a consumer dedupes on`() {
        val message = capturePublished(entry())

        assertThat(message.payload).isEqualTo("""{"outcome":"SENT"}""")

        val headers = metadata(message).headers.associate { it.key() to String(it.value()) }
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo("NotificationOutcome")
    }

    @Test
    fun `a real entry carries NO synthetic header - absence is what means real activity`() {
        val headers = metadata(capturePublished(entry(synthetic = false))).headers.map { it.key() }

        assertThat(headers).doesNotContain(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }

    @Test
    fun `a synthetic entry is taint-marked on the wire`() {
        val headers = metadata(capturePublished(entry(synthetic = true))).headers.map { it.key() }

        assertThat(headers).contains(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }
}
