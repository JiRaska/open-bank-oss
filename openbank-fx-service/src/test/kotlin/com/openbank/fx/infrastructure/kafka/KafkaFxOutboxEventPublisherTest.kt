// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The fx outbox is shared by two event families bound for two topics. A fixing routed to the
 * conversion topic would be read by audit and analytics as a conversion with no amounts; a
 * conversion routed to the fixing topic would be invisible to both. Each case asserts the OTHER
 * emitter was not touched, so a publisher that sent everything to one channel fails here.
 */
class KafkaFxOutboxEventPublisherTest {

    private val conversions = mockk<MutinyEmitter<String>>()
    private val fixings = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaFxOutboxEventPublisher(conversions, fixings)

    init {
        every { conversions.sendMessage(any()) } returns Uni.createFrom().voidItem()
        every { fixings.sendMessage(any()) } returns Uni.createFrom().voidItem()
    }

    @Test
    fun `a fixing event goes to the fixing channel only`() = runBlocking<Unit> {
        publisher.publish(entry("fx.fixing.published.v1"))

        verify(exactly = 1) { fixings.sendMessage(any()) }
        verify(exactly = 0) { conversions.sendMessage(any()) }
    }

    @Test
    fun `a conversion event goes to the conversion channel only`() = runBlocking<Unit> {
        publisher.publish(entry("fx.conversion.executed.v1"))

        verify(exactly = 1) { conversions.sendMessage(any()) }
        verify(exactly = 0) { fixings.sendMessage(any()) }
    }

    private fun entry(type: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = type,
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-05-30T12:00:00Z"),
        updatedAt = Instant.parse("2026-05-30T12:00:00Z"),
        sentAt = null,
        lastError = null,
    )
}
