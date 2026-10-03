// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

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
 * ADR-0003 N2/N3: the record key must be the aggregate id (so one case's events keep their
 * order on a single partition) and the event id must travel as the consumer-visible idempotency
 * key. Several services shipped a bare emitter with neither; this asserts the wire shape rather
 * than that the emitter was called.
 */
class KafkaKycOutboxEventPublisherTest {

    private val emitter = mockk<MutinyEmitter<String>>()
    private val publisher = KafkaKycOutboxEventPublisher(emitter)

    private val eventId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val aggregateId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun entry(synthetic: Boolean = false) = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = "KYC_CASE_APPROVED",
        payload = """{"eventType":"KYC_CASE_APPROVED"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-02-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-02-01T00:00:00Z"),
        sentAt = null,
        lastError = null,
        synthetic = synthetic,
    )

    private fun publishAndCapture(entry: OutboxEntry = entry()): Message<String> {
        val captured = slot<Message<String>>()
        every { emitter.sendMessage(capture(captured)) } returns Uni.createFrom().voidItem()
        runBlocking { publisher.publish(entry) }
        return captured.captured
    }

    private fun metadataOf(message: Message<String>): OutgoingKafkaRecordMetadata<*> =
        message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()

    @Test
    fun `the record key is the aggregate id, so one case's events keep their partition order`() {
        assertThat(metadataOf(publishAndCapture()).key).isEqualTo(aggregateId.toString())
    }

    @Test
    fun `the payload travels verbatim as the message body`() {
        assertThat(publishAndCapture().payload).isEqualTo("""{"eventType":"KYC_CASE_APPROVED"}""")
    }

    @Test
    fun `the event id and type travel as kafka headers a consumer can deduplicate on`() {
        val headers = metadataOf(publishAndCapture()).headers.associate { it.key() to String(it.value()) }

        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo("KYC_CASE_APPROVED")
    }

    @Test
    fun `a real (non-synthetic) entry carries no synthetic taint header`() {
        val headers = metadataOf(publishAndCapture()).headers.map { it.key() }

        assertThat(headers).doesNotContain(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }

    @Test
    fun `a synthetic entry is taint-marked on the wire`() {
        val headers = metadataOf(publishAndCapture(entry(synthetic = true))).headers.map { it.key() }

        assertThat(headers).contains(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }

    @Test
    fun `a broker failure propagates instead of being reported as dispatched`(): Unit = runBlocking {
        every { emitter.sendMessage(any<Message<String>>()) } returns
            Uni.createFrom().failure(IllegalStateException("broker down"))

        val thrown = runCatching { publisher.publish(entry()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }
}
