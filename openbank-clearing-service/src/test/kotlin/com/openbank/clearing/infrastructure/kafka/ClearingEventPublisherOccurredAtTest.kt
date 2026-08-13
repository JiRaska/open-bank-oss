// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * #3914 — the two clearing payloads must carry the BUSINESS event time.
 *
 * Without `occurredAt`, `AuditConsumer.eventTime()` returns null and `audit_entries.occurred_at`
 * records the consumer's INGEST time as the business time (GDPR Art. 30 "when", DORA Art. 17
 * evidence). Under consumer lag or a replay that value is arbitrarily wrong.
 *
 * Every assertion below is against an EXACT expected instant, never `isNotNull()` — a non-null
 * check passes against `Instant.EPOCH`, and a string emptiness check passes against the
 * four-character text `"null"` that Jackson's `asText()` yields for a JSON null. The
 * `Instant.parse` round-trip is load-bearing on its own: `OffsetDateTime.toString()` renders a
 * non-UTC offset as `...+01:00`, which `Instant.parse` REJECTS, and the consumer would then fall
 * back to ingest time — i.e. the payload would look fixed and behave exactly as broken.
 */
class ClearingEventPublisherOccurredAtTest {

    private val mapper = ObjectMapper()
    private val publisher = ClearingEventPublisherImpl(mapper, mockk())

    private val settledAt = OffsetDateTime.of(2026, 3, 1, 13, 34, 56, 0, ZoneOffset.ofHours(1))
    private val updatedAt = OffsetDateTime.of(2026, 3, 2, 9, 0, 0, 0, ZoneOffset.ofHours(2))

    private fun batch(settled: OffsetDateTime?) = ClearingBatch(
        id = UUID.randomUUID(),
        batchReference = "BATCH-1",
        rail = PaymentRail.SEPA_SCT,
        status = ClearingStatus.SETTLED,
        totalDebit = BigDecimal("10.00"),
        totalCredit = BigDecimal("10.00"),
        netPosition = BigDecimal.ZERO,
        itemCount = 1,
        cycleId = "C1",
        settledAt = settled,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun occurredAt(payload: String): Instant =
        Instant.parse(mapper.readTree(payload).get("occurredAt").asText())

    @Test
    fun `batch settled carries the settlement instant, normalised to UTC`() {
        val payload = publisher.batchSettledPayload(batch(settledAt))

        // 13:34:56+01:00 is 12:34:56Z — asserting the exact instant proves both that the right
        // domain fact was chosen AND that the offset was normalised rather than emitted verbatim.
        assertThat(occurredAt(payload)).isEqualTo(Instant.parse("2026-03-01T12:34:56Z"))
        assertThat(occurredAt(payload)).isNotEqualTo(Instant.EPOCH)
    }

    @Test
    fun `batch settled falls back to updatedAt when settledAt was never stamped`() {
        val payload = publisher.batchSettledPayload(batch(null))

        assertThat(occurredAt(payload)).isEqualTo(Instant.parse("2026-03-02T07:00:00Z"))
    }

    @Test
    fun `item cleared carries the row's last transition instant`() {
        val item = ClearingItem(
            id = UUID.randomUUID(),
            batchId = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            paymentReference = "PMT-1",
            debtorIban = "DE89370400440532013000",
            creditorIban = "GB29NWBK60161331926819",
            amount = BigDecimal("10.00"),
            status = ClearingStatus.SETTLED,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

        val payload = publisher.itemClearedPayload(item)

        assertThat(occurredAt(payload)).isEqualTo(Instant.parse("2026-03-02T07:00:00Z"))
    }
}
