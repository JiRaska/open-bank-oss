// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.interest.infrastructure.outbox

import com.openbank.interest.infrastructure.persistence.repository.InterestOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxEntry
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
 *
 * After ADR-0049 D3 migration, N2/N3 invariants are now enforced by [OutboxKafkaHeaders] from
 * libs (shared by all services), so the tests reference that directly.
 */
class InterestOutboxDispatchTest {

    private fun entry(eventType: String = "interest.accrual.posted.v1") = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = eventType,
        payload = """{"amount":42}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `statusAfterFailure stays FAILED below the cap and flips to DEAD at the cap`() {
        for (attempt in 1 until InterestOutboxRepositoryImpl.MAX_ATTEMPTS) {
            assertThat(InterestOutboxRepositoryImpl.statusAfterFailure(attempt))
                .isEqualTo(OutboxStatus.FAILED)
        }
        assertThat(InterestOutboxRepositoryImpl.statusAfterFailure(InterestOutboxRepositoryImpl.MAX_ATTEMPTS))
            .isEqualTo(OutboxStatus.DEAD)
        assertThat(InterestOutboxRepositoryImpl.statusAfterFailure(InterestOutboxRepositoryImpl.MAX_ATTEMPTS + 5))
            .isEqualTo(OutboxStatus.DEAD)
    }

    @Test
    fun `partition key is the aggregate id (N2)`() {
        val e = entry()
        assertThat(OutboxKafkaHeaders.partitionKey(e)).isEqualTo(e.aggregateId.toString())
    }

    @Test
    fun `headers carry the event id and event type (N3)`() {
        val e = entry(eventType = "interest.capitalization.posted.v1")
        val headers = OutboxKafkaHeaders.headersFor(e)

        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_ID]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY]).isEqualTo(e.eventId.toString())
        assertThat(headers[OutboxKafkaHeaders.HEADER_EVENT_TYPE]).isEqualTo(e.eventType)
    }
}
