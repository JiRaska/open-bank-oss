// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

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
 * ADR-0003 N2/N3: the record KEY must be the aggregate id (so one aggregate's events keep their
 * partition order) and the event id must travel as a consumer-visible dedup header. Neither is
 * observable from the payload, so nothing downstream can notice if this publisher drops them.
 */
class KafkaDocumentOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaDocumentOutboxEventPublisher(emitter)

    private val eventId = UUID.randomUUID()
    private val aggregateId = UUID.randomUUID()

    private fun entry(synthetic: Boolean = false) = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = "DocumentGenerated",
        payload = """{"documentId":"$aggregateId"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
        synthetic = synthetic,
    )

    private fun publishAndCapture(entry: OutboxEntry): Message<String> {
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry) }
        return captured.captured
    }

    private fun capture(): Message<String> = publishAndCapture(entry())

    @Test
    fun `the record key is the AGGREGATE id, not the event id`() {
        val message = capture()
        val meta = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

        assertThat(meta.key).isEqualTo(aggregateId.toString())
        assertThat(meta.key).isNotEqualTo(eventId.toString())
    }

    @Test
    fun `the payload is emitted verbatim`() {
        assertThat(capture().payload).isEqualTo("""{"documentId":"$aggregateId"}""")
    }

    @Test
    fun `the dedup and type headers travel with the record, as bytes`() {
        val meta = capture().getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        val headers = meta.headers.associate { it.key() to String(it.value()) }

        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo("DocumentGenerated")
    }

    @Test
    fun `a non-synthetic entry carries NO synthetic header — absence means real activity`() {
        val meta = capture().getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

        assertThat(meta.headers.map { it.key() }).doesNotContain(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }

    @Test
    fun `a synthetic entry is tainted on the wire`() {
        val meta = publishAndCapture(entry(synthetic = true))
            .getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

        assertThat(meta.headers.map { it.key() }).contains(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }
}
