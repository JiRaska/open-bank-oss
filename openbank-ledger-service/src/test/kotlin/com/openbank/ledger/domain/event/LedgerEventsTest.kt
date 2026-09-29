// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.ledger.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LedgerEventsTest {

    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `PeriodFrozen outbox payload preserves statutory evidence`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val event = PeriodFrozenEvent(
            aggregateId = id,
            version = 2,
            occurredAt = fixedInstant,
            periodLabel = "2025-12",
            periodType = "MONTH",
            periodFrom = LocalDate.of(2025, 12, 1),
            periodTo = LocalDate.of(2025, 12, 31),
            contentHash = "sha256:sealed-period",
            totalDebits = BigDecimal("125.50"),
            totalCredits = BigDecimal("125.50"),
            accountCount = 3,
            frozenBy = "checker-1",
            frozenAt = fixedInstant,
        )

        val payload = mapper.readTree(mapper.writeValueAsString(event))
        assertThat(payload["aggregateId"].asText()).isEqualTo(id.toString())
        assertThat(payload["aggregateType"].asText()).isEqualTo("ClosedPeriod")
        assertThat(payload["eventType"].asText()).isEqualTo("PeriodFrozen")
        assertThat(payload["version"].asLong()).isEqualTo(2)
        assertThat(payload["occurredAt"].asText()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(payload["periodLabel"].asText()).isEqualTo("2025-12")
        assertThat(payload["periodType"].asText()).isEqualTo("MONTH")
        assertThat(payload["periodFrom"].asText()).isEqualTo("2025-12-01")
        assertThat(payload["periodTo"].asText()).isEqualTo("2025-12-31")
        assertThat(payload["contentHash"].asText()).isEqualTo("sha256:sealed-period")
        assertThat(payload["totalDebits"].decimalValue()).isEqualByComparingTo("125.50")
        assertThat(payload["totalCredits"].decimalValue()).isEqualByComparingTo("125.50")
        assertThat(payload["accountCount"].asInt()).isEqualTo(3)
        assertThat(payload["frozenBy"].asText()).isEqualTo("checker-1")
        assertThat(payload["frozenAt"].asText()).isEqualTo("2026-01-01T00:00:00Z")
    }

    @Test
    fun `AccountingDayTransitioned outbox payload preserves transition`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val event = AccountingDayTransitionedEvent(
            aggregateId = id,
            version = 3,
            occurredAt = fixedInstant,
            businessDate = LocalDate.of(2025, 12, 31),
            fromStatus = "OPEN",
            toStatus = "CLOSED",
            transitionedBy = "operator-1",
        )

        val payload = mapper.readTree(mapper.writeValueAsString(event))
        assertThat(payload["aggregateId"].asText()).isEqualTo(id.toString())
        assertThat(payload["aggregateType"].asText()).isEqualTo("AccountingDay")
        assertThat(payload["eventType"].asText()).isEqualTo("AccountingDayTransitioned")
        assertThat(payload["version"].asLong()).isEqualTo(3)
        assertThat(payload["occurredAt"].asText()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(payload["businessDate"].asText()).isEqualTo("2025-12-31")
        assertThat(payload["fromStatus"].asText()).isEqualTo("OPEN")
        assertThat(payload["toStatus"].asText()).isEqualTo("CLOSED")
        assertThat(payload["transitionedBy"].asText()).isEqualTo("operator-1")
    }

    @Test
    fun `YearCloseAttested outbox payload preserves attestation`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000103")
        val event = YearCloseAttestedEvent(
            aggregateId = id,
            version = 4,
            occurredAt = fixedInstant,
            fiscalYear = 2025,
            contentHash = "sha256:sealed-year",
            totalDebits = BigDecimal("900.00"),
            totalCredits = BigDecimal("900.00"),
            accountCount = 8,
            attestedBy = "checker-2",
            attestedAt = fixedInstant,
        )

        val payload = mapper.readTree(mapper.writeValueAsString(event))
        assertThat(payload["aggregateId"].asText()).isEqualTo(id.toString())
        assertThat(payload["aggregateType"].asText()).isEqualTo("YearClose")
        assertThat(payload["eventType"].asText()).isEqualTo("YearCloseAttested")
        assertThat(payload["version"].asLong()).isEqualTo(4)
        assertThat(payload["occurredAt"].asText()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(payload["fiscalYear"].asInt()).isEqualTo(2025)
        assertThat(payload["contentHash"].asText()).isEqualTo("sha256:sealed-year")
        assertThat(payload["totalDebits"].decimalValue()).isEqualByComparingTo("900.00")
        assertThat(payload["totalCredits"].decimalValue()).isEqualByComparingTo("900.00")
        assertThat(payload["accountCount"].asInt()).isEqualTo(8)
        assertThat(payload["attestedBy"].asText()).isEqualTo("checker-2")
        assertThat(payload["attestedAt"].asText()).isEqualTo("2026-01-01T00:00:00Z")
    }

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
        assertThat(event.sourceService).isEqualTo("ledger-service")
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
