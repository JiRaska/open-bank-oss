// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(KafkaSanctionsOutboxEventPublisherTest.InMemoryKafkaResource::class)
class KafkaSanctionsOutboxEventPublisherTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("sanctions-events-out", "oversight-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var publisher: OutboxEventPublisher

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `a sanctions hit fans out a minimal oversight trigger without screening PII`(): Unit = runBlocking {
        val entry = entry(status = "HIT", score = 0.99)

        publisher.publish(entry)

        val trigger = oversightMessages(entry).single()
        assertThat(trigger.payload).contains("\"sourceService\":\"sanctions-service\"")
        assertThat(trigger.payload).contains("\"occurredAt\":\"2026-10-28T10:15:30Z\"")
        assertThat(trigger.payload).doesNotContain("Atomicity Subject", "partyId", "identifiers", "matches")
        assertThat(domainMessages(entry).single().payload).isEqualTo(entry.payload)
    }

    @Test
    fun `a high scoring potential hit also wakes oversight`(): Unit = runBlocking {
        val entry = entry(status = "POTENTIAL_HIT", score = 0.99)

        publisher.publish(entry)

        assertThat(oversightMessages(entry)).hasSize(1)
    }

    @Test
    fun `clear and low scoring potential hits stay on the domain topic only`(): Unit = runBlocking {
        val clear = entry(status = "CLEAR", score = 0.0)
        val potential = entry(status = "POTENTIAL_HIT", score = 0.70)

        publisher.publish(clear)
        publisher.publish(potential)

        assertThat(oversightMessages(clear) + oversightMessages(potential)).isEmpty()
        assertThat(domainMessages(clear) + domainMessages(potential)).hasSize(2)
    }

    private fun entry(status: String, score: Double): OutboxEntry {
        val id = UUID.randomUUID()
        return OutboxEntry(
            eventId = id,
            aggregateId = UUID.randomUUID(),
            eventType = "SanctionChecked",
            payload = """
                {"id":"$id","name":"Atomicity Subject","partyId":"${UUID.randomUUID()}",
                "status":"$status","overallScore":$score,"identifiers":{"passport":"secret"},
                "matches":[],"occurredAt":"2026-10-28T10:15:30Z"}
            """.trimIndent(),
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.parse("2026-10-28T10:15:30Z"),
            updatedAt = Instant.parse("2026-10-28T10:15:30Z"),
            sentAt = null,
            lastError = null,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun domainMessages(entry: OutboxEntry): List<Message<String>> =
        connector.sink<String>("sanctions-events-out").received()
            .map { it as Message<String> }
            .filter { eventId(it) == entry.eventId.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun oversightMessages(entry: OutboxEntry): List<Message<String>> =
        connector.sink<String>("oversight-events-out").received()
            .map { it as Message<String> }
            .filter { eventId(it) == entry.eventId.toString() }

    private fun eventId(message: Message<String>): String {
        val metadata = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
        return String(
            metadata.headers.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID).value(),
            StandardCharsets.UTF_8,
        )
    }
}
