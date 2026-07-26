// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessagePact
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven MESSAGE contract for the `swift.message.status-changed` event that
 * swift-service produces and transaction-service consumes (ADR-0108 settlement phase,
 * ADR-0104 D4 faithful-rails).
 *
 * The event carries the Swift message ID, its new status (VALIDATED/SENT/COMPLETED/REJECTED),
 * and a correlation reference to the originating payment saga.
 *
 * transaction-service (the provider) verifies this pact via its
 * TransactionPactProviderVerificationTest. The generated pact JSON is published to the
 * Pact Broker (pact.open-bank.tech) during CI (ADR-0092).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-swift-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class SwiftEventPactConsumerTest {

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-swift-service")
    fun swiftMessageStatusChangedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("swift-service has processed an MT103 and submitted to scheme gateway")
        .expectsToReceive("a swift.message.status-changed event with status COMPLETED")
        .withContent(
            newJsonBody { o ->
                o.uuid("swiftMessageId")
                o.stringType("paymentSagaRef")
                o.stringMatcher("status", "VALIDATED|SENT|COMPLETED|REJECTED", "COMPLETED")
                o.stringMatcher("messageType", "MT103|MT202|MX_PACS_008", "MT103")
                o.decimalType("amount", 1000.00)
                o.stringMatcher("currency", "[A-Z]{3}", "EUR")
                o.datetime("occurredAt", "yyyy-MM-dd'T'HH:mm:ss'Z'")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "swiftMessageStatusChangedPact")
    fun `status-changed event carries all fields needed by the settlement consumer`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(UUID.fromString(node.path("swiftMessageId").asText())).isNotNull()
        assertThat(node.path("paymentSagaRef").asText()).isNotBlank()
        assertThat(node.path("status").asText()).isEqualTo("COMPLETED")
        assertThat(node.path("messageType").asText()).isEqualTo("MT103")
        assertThat(node.path("amount").decimalValue()).isPositive()
        assertThat(node.path("currency").asText()).hasSize(3)
        assertThat(node.path("occurredAt").asText()).isNotBlank()
    }
}
