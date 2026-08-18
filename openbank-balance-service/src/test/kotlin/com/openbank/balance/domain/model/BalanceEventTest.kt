// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.domain.event.EventActor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Serialization round-trip for balance-service's Kafka events (issue #3994/#5256, fleet
 * follow-up to #5255's domestic-payment fix and the other nine slices: #5267, #5329, #5336,
 * #5337, #5343, #5344, #5349, #5351, plus #5369's audit-service subscription wiring).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field `BalanceEvent` carried no such key
 * and every audit row for balance-service fell back to `EventAttribution.TopicAttribution`'s
 * `openbank.balance.events` -> `balance-service` entry, correct but only TOPIC-sourced, not the
 * producer's own claim. Audit-service subscribes to that topic today
 * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
 * is a live attribution upgrade, not a forward-looking one.
 *
 * `eventType` (the [BalanceEventType] enum, serialised as `BALANCE_UPDATED` / `HOLD_PLACED` /
 * `HOLD_RELEASED` / `HOLD_EXPIRED`) is NOT touched — nothing outside balance-service reads any of
 * these strings by name (verified fleet-wide), so there is no load-bearing-rename risk here,
 * unlike account-service's #5267 fix.
 *
 * This is a serialised data class, not a hand-built map — the wire key exists only as the Kotlin
 * property name `sourceService`, same idiom as `actorId`/`actorType` (#3994's original fix).
 */
class BalanceEventTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val now = OffsetDateTime.parse("2026-08-16T10:00:00Z")

    private fun event(eventType: BalanceEventType) = BalanceEvent(
        eventId = UUID.randomUUID(),
        eventType = eventType,
        accountId = UUID.randomUUID(),
        currency = "CZK",
        amount = BigDecimal("10.00"),
        bookedAmount = BigDecimal("100.00"),
        availableAmount = BigDecimal("90.00"),
        reservedAmount = BigDecimal("10.00"),
        occurredAt = now,
        actorId = BalanceEventActors.API,
        actorType = EventActor.TYPE_SYSTEM,
        sourceService = "balance-service",
    )

    @Test
    fun `BalanceEvent carries eventType and sourceService for AuditConsumer attribution`() {
        val e = event(BalanceEventType.BALANCE_UPDATED)

        assertThat(e.sourceService).isEqualTo("balance-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(e))
        assertThat(node.get("eventType").asText()).isEqualTo("BALANCE_UPDATED")
        assertThat(node.get("sourceService").asText()).isEqualTo("balance-service")
    }

    @Test
    fun `sourceService defaults to balance-service when a caller omits it`() {
        val e = BalanceEvent(
            eventId = UUID.randomUUID(),
            eventType = BalanceEventType.HOLD_PLACED,
            accountId = UUID.randomUUID(),
            currency = "CZK",
            amount = BigDecimal("5.00"),
            bookedAmount = BigDecimal("100.00"),
            availableAmount = BigDecimal("95.00"),
            reservedAmount = BigDecimal("5.00"),
            occurredAt = now,
        )

        assertThat(e.sourceService).isEqualTo("balance-service")
    }

    @Test
    fun `HOLD_RELEASED and HOLD_EXPIRED payloads also carry sourceService`() {
        listOf(BalanceEventType.HOLD_RELEASED, BalanceEventType.HOLD_EXPIRED).forEach { type ->
            val node = objectMapper.readTree(objectMapper.writeValueAsString(event(type)))
            assertThat(node.get("sourceService").asText()).isEqualTo("balance-service")
        }
    }
}
