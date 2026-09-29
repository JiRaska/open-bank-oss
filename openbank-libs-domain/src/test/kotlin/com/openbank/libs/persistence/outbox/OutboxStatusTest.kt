// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Property-level coverage for [OutboxMessage] and [OutboxEntry] (previously PIT NO_COVERAGE: no
 * test anywhere read these fields back off a constructed instance, so a mutant that swaps a
 * getter's return value for `""`/`null`/`false` went undetected).
 */
class OutboxStatusTest {

    @Test
    fun `OutboxMessage carries the constructor values through its properties`() {
        val aggregateId = UUID.randomUUID()
        val message = OutboxMessage(
            aggregateId = aggregateId,
            eventType = "payment.settled",
            payload = """{"amount":100}""",
        )

        assertThat(message.aggregateId).isEqualTo(aggregateId)
        assertThat(message.eventType).isEqualTo("payment.settled")
        assertThat(message.payload).isEqualTo("""{"amount":100}""")
    }

    @Test
    fun `OutboxMessage defaults synthetic to false for real activity`() {
        val message = OutboxMessage(
            aggregateId = UUID.randomUUID(),
            eventType = "account.opened",
            payload = "{}",
        )

        assertThat(message.synthetic).isFalse()
    }

    @Test
    fun `OutboxMessage honours an explicit synthetic = true`() {
        val message = OutboxMessage(
            aggregateId = UUID.randomUUID(),
            eventType = "account.opened",
            payload = "{}",
            synthetic = true,
        )

        assertThat(message.synthetic).isTrue()
    }

    @Test
    fun `OutboxMessage stamps createdAt near now, not a fixed sentinel`() {
        val before = Instant.now()
        val message = OutboxMessage(aggregateId = UUID.randomUUID(), eventType = "x", payload = "{}")
        val after = Instant.now()

        // Recency, not non-nullity (ADR-0050 N... / the Instant.EPOCH sentinel trap): a default
        // of Instant.EPOCH would still be "not null" but would starve real traffic behind a
        // permanently-oldest backlog row.
        assertThat(message.createdAt).isBetween(before, after)
    }

    private fun entry(payload: String = "{}", lastError: String? = null, sentAt: Instant? = null) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "x.created",
        payload = payload,
        status = OutboxStatus.SENT,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = sentAt,
        lastError = lastError,
    )

    @Test
    fun `OutboxEntry carries the payload through unchanged`() {
        assertThat(entry(payload = """{"k":"v"}""").payload).isEqualTo("""{"k":"v"}""")
    }

    @Test
    fun `OutboxEntry carries a non-null lastError through unchanged`() {
        assertThat(entry(lastError = "kafka down").lastError).isEqualTo("kafka down")
    }

    @Test
    fun `OutboxEntry keeps lastError null when there was no failure`() {
        assertThat(entry(lastError = null).lastError).isNull()
    }

    @Test
    fun `OutboxEntry carries a non-null sentAt through unchanged`() {
        val sentAt = Instant.parse("2026-01-01T00:00:00Z")
        assertThat(entry(sentAt = sentAt).sentAt).isEqualTo(sentAt)
    }

    @Test
    fun `OutboxEntry keeps sentAt null before it has ever been dispatched`() {
        assertThat(entry(sentAt = null).sentAt).isNull()
    }
}
