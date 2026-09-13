// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Unit coverage for [TransactionSettledEvent] (emitted at the end of the settlement saga). */
class TransactionSettledEventTest {

    @Test
    fun `carries the journal and originating payment ids and the settlement dates`() {
        val aggregateId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        val originatingPaymentId = UUID.randomUUID()
        val settledAt = Instant.parse("2026-07-14T12:00:00Z")
        val bookingDate = LocalDate.of(2026, 7, 14)

        val event = TransactionSettledEvent(
            aggregateId = aggregateId,
            version = 4L,
            referenceNumber = "REF-5",
            journalId = journalId,
            originatingPaymentId = originatingPaymentId,
            bookingDate = bookingDate,
            settledAt = settledAt,
            occurredAt = settledAt,
        )

        assertThat(event.aggregateType).isEqualTo("Transaction")
        assertThat(event.eventType).isEqualTo("TransactionSettled")
        assertThat(event.journalId).isEqualTo(journalId)
        assertThat(event.originatingPaymentId).isEqualTo(originatingPaymentId)
        assertThat(event.bookingDate).isEqualTo(bookingDate)
        assertThat(event.settledAt).isEqualTo(settledAt)
        assertThat(event.eventId).isNotNull()
        // AuditConsumer attribution (#3994/#5256): read as the strongest (EVENT-sourced) claim.
        assertThat(event.sourceService).isEqualTo("transaction-service")
    }

    @Test
    fun `originatingPaymentId is optional for internal transfers with no originating rail payment`() {
        val event = TransactionSettledEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            referenceNumber = "REF-6",
            journalId = UUID.randomUUID(),
            originatingPaymentId = null,
            bookingDate = LocalDate.of(2026, 7, 14),
            settledAt = Instant.parse("2026-07-14T12:00:00Z"),
            occurredAt = Instant.parse("2026-07-14T12:00:00Z"),
        )

        assertThat(event.originatingPaymentId).isNull()
        assertThat(event).isEqualTo(event.copy())
        assertThat(event.toString()).contains("REF-6")
        assertThat(event.hashCode()).isEqualTo(event.copy().hashCode())
    }
}
