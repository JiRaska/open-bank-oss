// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.infrastructure.kafka

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
 * ADR-0003 N2/N3: the relayed record must carry the aggregate id as its partition key (ordering)
 * and the event id / type as headers (consumer-side dedup). Asserts the metadata actually attached
 * to the emitted [Message], not that the emitter was called.
 */
class KafkaFxOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaFxOutboxEventPublisher(emitter)

    private fun entry(synthetic: Boolean = false) = OutboxEntry(
        eventId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        aggregateId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        eventType = "fx.conversion.executed.v1",
        payload = """{"conversionId":"22222222-2222-2222-2222-222222222222"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-01-02T03:04:05Z"),
        updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
        sentAt = null,
        lastError = null,
        synthetic = synthetic,
    )

    private fun emit(entry: OutboxEntry): Message<String> {
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry) }
        return captured.captured
    }

    private fun Message<String>.kafkaMetadata(): OutgoingKafkaRecordMetadata<*> =
        getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

    private fun Message<String>.header(name: String): String? =
        kafkaMetadata().headers.lastHeader(name)?.value()?.decodeToString()

    @Test
    fun `the record payload is the outbox payload verbatim`(): Unit = runBlocking {
        val e = entry()

        assertThat(emit(e).payload).isEqualTo(e.payload)
    }

    @Test
    fun `the partition key is the aggregate id, not the event id`() {
        val e = entry()

        val key = emit(e).kafkaMetadata().key

        assertThat(key).isEqualTo(e.aggregateId.toString())
        assertThat(key).isNotEqualTo(e.eventId.toString())
        assertThat(key).isEqualTo(OutboxKafkaHeaders.partitionKey(e))
    }

    @Test
    fun `event id, idempotency key and event type reach the record headers`() {
        val e = entry()

        val message = emit(e)

        assertThat(message.header(OutboxKafkaHeaders.HEADER_EVENT_ID)).isEqualTo(e.eventId.toString())
        assertThat(message.header(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)).isEqualTo(e.eventId.toString())
        assertThat(message.header(OutboxKafkaHeaders.HEADER_EVENT_TYPE)).isEqualTo(e.eventType)
    }

    @Test
    fun `a real record carries no synthetic header and a tainted one does`() {
        assertThat(emit(entry(synthetic = false)).header(OutboxKafkaHeaders.HEADER_SYNTHETIC)).isNull()
        assertThat(emit(entry(synthetic = true)).header(OutboxKafkaHeaders.HEADER_SYNTHETIC)).isNotNull()
    }

    @Test
    fun `every header the canonical builder produces is on the record`() {
        val e = entry(synthetic = true)

        val message = emit(e)

        OutboxKafkaHeaders.headersFor(e).forEach { (name, value) ->
            assertThat(message.header(name)).describedAs(name).isEqualTo(value)
        }
    }
}
