// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.messaging

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
 * The PSD2 outbox-to-Kafka transport: verifies the payload, partition key (= aggregateId, ADR-0050
 * N2) and the `ce-id` / `idempotency-key` / `ce-type` headers ([OutboxKafkaHeaders]) all land on the
 * outgoing record exactly as the shared header contract prescribes.
 */
class KafkaPsd2OutboxEventPublisherTest {

    @Test
    fun `publish sends the payload with the aggregate partition key and outbox headers`(): Unit = runBlocking {
        val emitter = mockk<MutinyEmitter<String>>(relaxed = true)
        val messageSlot = slot<Message<String>>()
        every { emitter.sendMessage(capture(messageSlot)) } returns Uni.createFrom().voidItem()

        val entry = OutboxEntry(
            eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            aggregateId = UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
            eventType = "psd2.consent.created",
            payload = """{"eventType":"psd2.consent.created"}""",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

        KafkaPsd2OutboxEventPublisher(emitter).publish(entry)

        verify(exactly = 1) { emitter.sendMessage(any()) }
        assertThat(messageSlot.captured.payload).isEqualTo("""{"eventType":"psd2.consent.created"}""")

        val meta = messageSlot.captured.getMetadata(OutgoingKafkaRecordMetadata::class.java)
        assertThat(meta).isPresent
        assertThat(meta.get().key).isEqualTo(OutboxKafkaHeaders.partitionKey(entry))

        val headers = meta.get().headers.associate { it.key() to String(it.value()) }
        val expected = OutboxKafkaHeaders.headersFor(entry)
        expected.forEach { (k, v) -> assertThat(headers[k]).isEqualTo(v) }
    }
}
