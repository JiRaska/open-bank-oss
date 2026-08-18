// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Serialization round-trip for card-issuance-service's outbox events (issue #3994/#5256, fleet
 * follow-up to #5255's domestic-payment fix and the other eleven slices: #5267, #5329, #5336,
 * #5337, #5343, #5344, #5349, #5351, #5369, #5374, #5376).
 *
 * `CardEvent` is serialised via `ObjectMapper.writeValueAsString` in
 * `CardService.outboxMessage` — a serialised sealed class, not a hand-built map — so
 * `sourceService` exists on the wire only as this Kotlin property name, declared once on the
 * sealed base rather than repeated per subtype.
 *
 * `AuditConsumer.resolveSourceService` (audit-service) reads `sourceService` as the strongest
 * (EVENT-sourced) attribution. Audit-service subscribes to `openbank.cards.events` today
 * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
 * is a live attribution upgrade, not a forward-looking one.
 *
 * `eventType` (`CardService.EVENT_CARD_ISSUED` etc., e.g. `"card.issued.v1"`) is NOT touched —
 * verified fleet-wide that nothing outside card-issuance-service reads these strings by name, so
 * there is no load-bearing-rename risk here.
 */
class CardEventsTest {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Test
    fun `CardIssued carries sourceService on the wire`() {
        val event = CardIssued(
            cardId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            cardType = CardType.DEBIT,
            network = CardNetwork.VISA,
            maskedPan = "**** **** **** 1234",
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("card-issuance-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("sourceService").asText()).isEqualTo("card-issuance-service")
        assertThat(node.get("cardId").asText()).isEqualTo(event.cardId.toString())
    }

    @Test
    fun `CardStatusChanged carries sourceService on the wire`() {
        val event = CardStatusChanged(
            cardId = UUID.randomUUID(),
            previousStatus = CardStatus.PENDING,
            newStatus = CardStatus.ACTIVE,
            reason = "Manual activation",
            changedBy = "ops-user",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("sourceService").asText()).isEqualTo("card-issuance-service")
    }

    @Test
    fun `CardLimitsChanged carries sourceService on the wire`() {
        val event = CardLimitsChanged(
            cardId = UUID.randomUUID(),
            dailyLimitMinorUnits = 50000,
            monthlyLimitMinorUnits = 500000,
            changedBy = "ops-user",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("sourceService").asText()).isEqualTo("card-issuance-service")
    }

    @Test
    fun `CardControlsChanged carries sourceService on the wire`() {
        val event = CardControlsChanged(
            cardId = UUID.randomUUID(),
            contactlessEnabled = true,
            onlineEnabled = true,
            atmEnabled = false,
            abroadEnabled = false,
            changedBy = "ops-user",
            occurredAt = now,
        )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("sourceService").asText()).isEqualTo("card-issuance-service")
    }
}
