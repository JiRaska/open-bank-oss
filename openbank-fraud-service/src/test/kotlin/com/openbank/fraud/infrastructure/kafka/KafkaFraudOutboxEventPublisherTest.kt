// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KafkaFraudOutboxEventPublisherTest {

    @Test
    fun `publish sends the payload wrapped as a Message`() {
        val emitter = mockk<MutinyEmitter<String>>()
        val messageSlot = slot<Message<String>>()
        every { emitter.sendMessage(capture(messageSlot)) } returns Uni.createFrom().voidItem()

        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = UUID.randomUUID(),
            eventType = "fraud.hold_changed",
            payload = """{"partyId":"x"}""",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

        runBlocking { KafkaFraudOutboxEventPublisher(emitter).publish(entry) }

        assertThat(messageSlot.captured.payload).isEqualTo(entry.payload)
    }
}
