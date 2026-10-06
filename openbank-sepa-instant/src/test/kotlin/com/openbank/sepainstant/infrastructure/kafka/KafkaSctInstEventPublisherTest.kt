// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.sepainstant.domain.event.SctInstPaymentSubmitted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** The durable writer stores the established four-field Kafka payload verbatim. */
class KafkaSctInstEventPublisherTest {
    @Test
    fun `outbox payload carries the same sourceService attribution and identifiers`() {
        val mapper = ObjectMapper().registerModule(JavaTimeModule())
        val event = SctInstPaymentSubmitted(
            paymentId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            creditorIban = "FR7630006000011234567890189",
            amount = BigDecimal("50.00"),
            currency = "EUR",
            endToEndId = "E2E-sepa-instant",
            occurredAt = OffsetDateTime.parse("2026-08-17T10:00:00Z"),
        )

        val node = mapper.readTree(SctInstEventPayloadCodec(mapper).encode(event))
        assertThat(node.size()).isEqualTo(4)
        assertThat(node.get("sourceService").asText()).isEqualTo("sepa-instant")
        assertThat(node.get("paymentId").asText()).isEqualTo(event.paymentId.toString())
        assertThat(node.get("type").asText()).isEqualTo("SctInstPaymentSubmitted")
        // The existing Jackson configuration writes JavaTime as epoch seconds; retain that wire shape.
        assertThat(node.get("occurredAt").isNumber).isTrue()
    }
}
