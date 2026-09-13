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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Serialization round-trip for clearing-service's Kafka event payloads (issue #3994/#5256, fleet
 * follow-up to #5255's domestic-payment fix and the other seven money-path slices: #5267, #5329,
 * #5336, #5337, #5343, #5344).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field neither `batch.settled` nor
 * `item.cleared` carried such a key, and every audit row for clearing-service fell back to
 * `EventAttribution.TopicAttribution`'s `openbank.clearing.batch.event` -> `clearing-service`
 * entry, correct but only TOPIC-sourced, not the producer's own claim. Audit-service subscribes
 * to that topic today (`openbank-audit-service/src/main/resources/application.yaml`'s
 * consumed-topics list), so this is a live attribution upgrade, not a forward-looking one.
 *
 * `eventType` ("openbank.clearing.batch.settled" / "openbank.clearing.item.cleared") is NOT
 * touched — nothing outside clearing-service reads either string by name (verified fleet-wide),
 * so there is no load-bearing-rename risk here, unlike account-service's #5267 fix.
 */
class ClearingEventPublisherSourceServiceTest {

    private val mapper = ObjectMapper()
    private val publisher = ClearingEventPublisherImpl(mapper, mockk())

    private val settledAt = OffsetDateTime.of(2026, 3, 1, 13, 34, 56, 0, ZoneOffset.ofHours(1))
    private val updatedAt = OffsetDateTime.of(2026, 3, 2, 9, 0, 0, 0, ZoneOffset.ofHours(2))

    private fun batch() = ClearingBatch(
        id = UUID.randomUUID(),
        batchReference = "BATCH-1",
        rail = PaymentRail.SEPA_SCT,
        status = ClearingStatus.SETTLED,
        totalDebit = BigDecimal("10.00"),
        totalCredit = BigDecimal("10.00"),
        netPosition = BigDecimal.ZERO,
        itemCount = 1,
        cycleId = "C1",
        settledAt = settledAt,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun item() = ClearingItem(
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

    @Test
    fun `batchSettledPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = mapper.readTree(publisher.batchSettledPayload(batch()))

        assertThat(node.get("eventType").asText()).isEqualTo("openbank.clearing.batch.settled")
        assertThat(node.get("sourceService").asText()).isEqualTo("clearing-service")
    }

    @Test
    fun `itemClearedPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = mapper.readTree(publisher.itemClearedPayload(item()))

        assertThat(node.get("eventType").asText()).isEqualTo("openbank.clearing.item.cleared")
        assertThat(node.get("sourceService").asText()).isEqualTo("clearing-service")
    }
}
