// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.aml.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Pure-function coverage of the outbox dispatch invariants (ADR-0050) that need no Quarkus context:
 *  - N5: the terminal-vs-retry decision parks a row as DEAD only once attempts reach the cap.
 *  - N2: the partition key is the aggregate id.
 *  - N3: the produced headers carry the event id (ce-id / idempotency-key) and the event type
 *    (ce-type).
 */
class AmlOutboxDispatchTest {

    private fun entry(eventType: String = "aml.case.created.v1") = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = eventType,
        payload = """{"caseId":"x"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2024-01-15T10:00:00Z"),
        updatedAt = Instant.parse("2024-01-15T10:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `statusAfterFailure stays FAILED below the cap and flips to DEAD at the cap`() {
        for (attempt in 1 until OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS) {
            assertThat(OutboxFailurePolicy.statusAfterFailure(attempt))
                .isEqualTo(OutboxStatus.FAILED)
        }
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS))
            .isEqualTo(OutboxStatus.DEAD)
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS + 5))
            .isEqualTo(OutboxStatus.DEAD)
    }

    @Test
    fun `partition key is the aggregate id (N2)`() {
        val e = entry()
        assertThat(OutboxKafkaHeaders.partitionKey(e)).isEqualTo(e.aggregateId.toString())
    }

    @Test
    fun `headers carry the event id and event type (N3)`() {
        val e = entry(eventType = "aml.case.status_changed.v1")
        val headers = OutboxKafkaHeaders.headersFor(e)

        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo(e.eventType)
    }
}
