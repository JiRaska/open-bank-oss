// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.fraud.contract

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
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven MESSAGE contract for the `openbank.transactions.transaction.initiated`
 * event that fraud-service consumes from transaction-service (ADR-0084 §2).
 *
 * The consumer (fraud-service) reads {aggregateId, sourceAccountId, amount, currencyCode}
 * to build velocity counters and run fraud-risk scoring. transaction-service (the provider)
 * verifies this pact via TransactionPactProviderVerificationTest.
 *
 * The generated pact JSON is published to the Pact Broker (pact.open-bank.tech) during CI
 * (ADR-0092).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-transaction-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class TransactionInitiatedPactConsumerTest {

    @Pact(consumer = "openbank-fraud-service", provider = "openbank-transaction-service")
    fun transactionInitiatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("transaction-service has initiated a payment transaction")
        .expectsToReceive("a transaction.initiated event for fraud screening")
        .withContent(
            newJsonBody { o ->
                o.uuid("aggregateId")
                o.uuid("sourceAccountId")
                o.decimalType("amount", 250.00)
                o.stringMatcher("currencyCode", "[A-Z]{3}", "CZK")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "transactionInitiatedPact")
    fun `transaction-initiated event carries all fields needed by fraud screening`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("sourceAccountId").asText())).isNotNull()
        assertThat(node.path("amount").decimalValue()).isGreaterThan(BigDecimal.ZERO)
        assertThat(node.path("currencyCode").asText()).hasSize(3)
    }
}
