// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit coverage for the regulatory-grade outbox dispatch invariants (ADR-0050):
 * N2 (deterministic partition key), N3 (carried idempotency id), N5 (bounded retries → DEAD).
 * These are pure functions on libs types, so no Quarkus/DB/Kafka context is needed.
 */
class SddOutboxDispatchTest {

    private fun entry(
        eventId: UUID = UUID.randomUUID(),
        aggregateId: UUID = UUID.randomUUID(),
        eventType: String = "sdd.collection.authorised.v1",
    ) = OutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = eventType,
        payload = """{"hello":"world"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    // --- N5: bounded retries → terminal DEAD ---

    @Test
    fun `failure below the cap keeps the row retryable (FAILED)`() {
        assertThat(OutboxFailurePolicy.statusAfterFailure(1))
            .isEqualTo(OutboxStatus.FAILED)
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1))
            .isEqualTo(OutboxStatus.FAILED)
    }

    @Test
    fun `reaching the attempt cap parks the row as terminal DEAD`() {
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS))
            .isEqualTo(OutboxStatus.DEAD)
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS + 5))
            .isEqualTo(OutboxStatus.DEAD)
    }

    // --- N2: deterministic partition key = aggregate id ---

    @Test
    fun `partition key is the aggregate id so per-mandate order is preserved`() {
        val aggregateId = UUID.randomUUID()
        val a = entry(aggregateId = aggregateId)
        val b = entry(aggregateId = aggregateId)

        assertThat(OutboxKafkaHeaders.partitionKey(a)).isEqualTo(aggregateId.toString())
        // Two events for the same aggregate route to the same key (same partition).
        assertThat(OutboxKafkaHeaders.partitionKey(a))
            .isEqualTo(OutboxKafkaHeaders.partitionKey(b))
    }

    // --- N3: event.id carried as the consumer-visible idempotency key ---

    @Test
    fun `headers carry event id as ce-id and idempotency-key plus the event type`() {
        val e = entry()
        val headers = OutboxKafkaHeaders.headersFor(e)

        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo(e.eventType)
    }
}
