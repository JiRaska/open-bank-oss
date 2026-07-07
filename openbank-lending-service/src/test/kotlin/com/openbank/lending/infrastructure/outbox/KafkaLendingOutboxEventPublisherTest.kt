// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.outbox

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
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * The Kafka publisher must follow the canonical outbox addressing (ADR-0050 N2/N3): partition key =
 * aggregate id (ordering per aggregate) and the ce-id / idempotency-key / ce-type headers (consumer
 * dedup). A bare payload-only send would silently break downstream ordering and dedup.
 */
class KafkaLendingOutboxEventPublisherTest {

    @Test
    fun `publishes the payload keyed by aggregate id with the canonical dedup headers`(): Unit = runBlocking {
        val emitter = mockk<MutinyEmitter<String>>()
        val messageSlot = slot<Message<String>>()
        every { emitter.sendMessage(capture(messageSlot)) } returns Uni.createFrom().voidItem()

        val eventId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val aggregateId = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val entry = OutboxEntry(
            eventId = eventId,
            aggregateId = aggregateId,
            eventType = "loan.disbursed",
            payload = """{"loanId":"77777777-7777-7777-7777-777777777777"}""",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            sentAt = null,
            lastError = null,
        )

        KafkaLendingOutboxEventPublisher(emitter).publish(entry)

        val message = messageSlot.captured
        assertThat(message.payload).isEqualTo(entry.payload)

        val metadata = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        // N2: all events of one aggregate share a partition, preserving their order.
        assertThat(metadata.key).isEqualTo(aggregateId.toString())

        val headers = metadata.headers.associate { it.key() to String(it.value(), StandardCharsets.UTF_8) }
        // N3: the event id doubles as the consumer-visible idempotency key.
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo("loan.disbursed")
    }
}
