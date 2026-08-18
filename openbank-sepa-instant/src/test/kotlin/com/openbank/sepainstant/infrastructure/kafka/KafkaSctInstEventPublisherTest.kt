// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.sepainstant.domain.event.SctInstPaymentSubmitted
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire-payload proof for issue #3994/#5256: `KafkaSctInstEventPublisher.publish` builds a
 * HAND-BUILT map (`mapOf("type" to ..., "paymentId" to ..., "occurredAt" to ...)`), not a
 * serialised data class — so a `sourceService` field on `SctInstEvent` alone would never reach
 * the wire without also being copied into that map. This test proves the actual JSON the emitter
 * sends, not just that the domain event carries the field.
 *
 * `AuditConsumer.resolveSourceService` (audit-service) reads `sourceService` as the strongest
 * (EVENT-sourced) attribution. `EventAttribution.TopicAttribution` already maps
 * `openbank.sepa.instant.events` -> `sepa-instant` correctly, but only as TOPIC-sourced, and
 * audit-service subscribes to this topic today
 * (`openbank-audit-service/src/main/resources/application.yaml`'s consumed-topics list), so this
 * is a live attribution upgrade. sepa-instant is a money-path service
 * (`rules.yaml: money_path_services`).
 */
class KafkaSctInstEventPublisherTest {

    private lateinit var emitter: MutinyEmitter<String>
    private lateinit var objectMapper: ObjectMapper
    private lateinit var publisher: KafkaSctInstEventPublisher

    @BeforeEach
    fun setUp() {
        emitter = mockk()
        objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        publisher = KafkaSctInstEventPublisher(emitter, objectMapper)
    }

    @Test
    fun `publish sends a payload carrying sourceService for AuditConsumer attribution`() {
        val sent = slot<String>()
        every { emitter.send(capture(sent)) } returns Uni.createFrom().voidItem()
        val event = SctInstPaymentSubmitted(
            paymentId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            creditorIban = "FR7630006000011234567890189",
            amount = BigDecimal("50.00"),
            currency = "EUR",
            endToEndId = "E2E-sepa-instant",
            occurredAt = OffsetDateTime.parse("2026-08-17T10:00:00Z"),
        )

        publisher.publish(event)

        val node = objectMapper.readTree(sent.captured)
        assertThat(node.get("sourceService").asText()).isEqualTo("sepa-instant")
        assertThat(node.get("paymentId").asText()).isEqualTo(event.paymentId.toString())
        assertThat(node.get("type").asText()).isEqualTo("SctInstPaymentSubmitted")
    }
}
