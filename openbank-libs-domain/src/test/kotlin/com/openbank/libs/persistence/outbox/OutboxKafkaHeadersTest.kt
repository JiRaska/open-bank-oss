// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OutboxKafkaHeadersTest {

    private fun entry(
        eventId: UUID = UUID.randomUUID(),
        aggregateId: UUID = UUID.randomUUID(),
        eventType: String = "account.created",
    ) = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = eventType,
        payload = "{}",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `partition key is the aggregate id (N2)`() {
        val aggregateId = UUID.randomUUID()
        assertThat(OutboxKafkaHeaders.partitionKey(entry(aggregateId = aggregateId)))
            .isEqualTo(aggregateId.toString())
    }

    @Test
    fun `headers carry event id as ce-id and idempotency-key, plus event type (N3)`() {
        val eventId = UUID.randomUUID()
        val headers = OutboxKafkaHeaders.headersFor(entry(eventId = eventId, eventType = "payment.settled"))

        assertThat(headers).containsEntry(OutboxKafkaHeaders.HEADER_EVENT_ID, eventId.toString())
        assertThat(headers).containsEntry(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY, eventId.toString())
        assertThat(headers).containsEntry(OutboxKafkaHeaders.HEADER_EVENT_TYPE, "payment.settled")
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID])
            .isEqualTo(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY])
    }

    @Test
    fun `synthetic entry carries the durable taint header`() {
        val headers = OutboxKafkaHeaders.headersFor(entry().copy(synthetic = true))

        assertThat(headers).containsEntry(OutboxKafkaHeaders.HEADER_SYNTHETIC, "true")
    }

    @Test
    fun `real entry does not carry a synthetic header`() {
        val headers = OutboxKafkaHeaders.headersFor(entry())

        assertThat(headers).doesNotContainKey(OutboxKafkaHeaders.HEADER_SYNTHETIC)
    }

    @Test
    fun `an entry with a captured traceparent emits the W3C header unchanged`() {
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        val headers = OutboxKafkaHeaders.headersFor(entry().copy(traceparent = traceparent))

        assertThat(headers).containsEntry(OutboxKafkaHeaders.HEADER_TRACEPARENT, traceparent)
    }

    @Test
    fun `an entry without a traceparent emits no trace header`() {
        assertThat(OutboxKafkaHeaders.headersFor(entry())).doesNotContainKey(OutboxKafkaHeaders.HEADER_TRACEPARENT)
        assertThat(OutboxKafkaHeaders.headersFor(entry().copy(traceparent = " "))).doesNotContainKey(
            OutboxKafkaHeaders.HEADER_TRACEPARENT,
        )
    }
}
