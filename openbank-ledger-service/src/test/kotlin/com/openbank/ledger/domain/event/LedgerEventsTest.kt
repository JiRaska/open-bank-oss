// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.ledger.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LedgerEventsTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `JournalPostedEvent carries correct aggregate metadata`() {
        val event = JournalPostedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            entryNumber = 42L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 3, 15),
            lineCount = 2,
            occurredAt = fixedInstant,
        )

        assertThat(event.aggregateType).isEqualTo("JournalEntry")
        assertThat(event.eventType).isEqualTo("JournalPosted")
        assertThat(event.eventId).isNotNull()
        assertThat(event.occurredAt).isEqualTo(fixedInstant)
        assertThat(event.entryNumber).isEqualTo(42L)
        assertThat(event.lineCount).isEqualTo(2)
    }

    @Test
    fun `JournalReversedEvent carries original journal reference`() {
        val originalId = UUID.randomUUID()
        val event = JournalReversedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            originalJournalId = originalId,
            transactionId = UUID.randomUUID(),
            reason = "Customer dispute",
            occurredAt = fixedInstant,
        )

        assertThat(event.aggregateType).isEqualTo("JournalEntry")
        assertThat(event.eventType).isEqualTo("JournalReversed")
        assertThat(event.originalJournalId).isEqualTo(originalId)
        assertThat(event.reason).isEqualTo("Customer dispute")
    }

    @Test
    fun `each event instance gets unique eventId`() {
        val event1 = JournalPostedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            entryNumber = 1L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 1),
            lineCount = 2,
            occurredAt = fixedInstant,
        )
        val event2 = JournalPostedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            entryNumber = 2L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 1),
            lineCount = 2,
            occurredAt = fixedInstant,
        )

        assertThat(event1.eventId).isNotEqualTo(event2.eventId)
    }
}
