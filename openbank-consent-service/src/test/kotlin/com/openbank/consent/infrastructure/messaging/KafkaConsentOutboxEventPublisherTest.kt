// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.infrastructure.messaging

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KafkaConsentOutboxEventPublisherTest {

    @Test
    fun `publish sends a message with correct payload, partition key and headers`(): Unit = runBlocking {
        val emitter = mockk<MutinyEmitter<String>>(relaxed = true)
        val messageSlot = slot<Message<String>>()
        every { emitter.sendMessage(capture(messageSlot)) } returns Uni.createFrom().voidItem()

        val entry = OutboxEntry(
            eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            eventType = "ConsentGranted",
            payload = """{"eventType":"ConsentGranted"}""",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

        KafkaConsentOutboxEventPublisher(emitter).publish(entry)

        verify(exactly = 1) { emitter.sendMessage(any()) }
        assertThat(messageSlot.captured.payload).isEqualTo("""{"eventType":"ConsentGranted"}""")
        val meta = messageSlot.captured.getMetadata(
            io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata::class.java,
        )
        assertThat(meta).isPresent
        assertThat(meta.get().key).isEqualTo(OutboxKafkaHeaders.partitionKey(entry))
    }
}
