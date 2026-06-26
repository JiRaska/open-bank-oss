// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.contract

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
 * Consumer-driven MESSAGE contract for the `AccountCreated` event balance-service consumes to
 * initialize a zero balance (ADR-0073, [com.openbank.balance.infrastructure.kafka.BalanceInitConsumer]).
 *
 * This is the first async (message) pact in the repo — it asserts the event carries the three fields
 * the consumer actually reads (`eventType`, `aggregateId`, `currency`); the producer (account-service)
 * verifies it via AccountEventPactProviderVerificationTest. The generated pact is committed to
 * `pacts/openbank-balance-service-openbank-account-service.json` (git-pact, ADR-0063) — a new
 * consumer/provider pair, so it does not collide with the existing balance→ledger REST pact.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-account-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class AccountCreatedMessagePactConsumerTest {

    @Pact(consumer = "openbank-balance-service", provider = "openbank-account-service")
    fun accountCreatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an account has been created")
        .expectsToReceive("an AccountCreated event")
        .withContent(
            newJsonBody { o ->
                // The consumer filters on eventType (exact) and reads aggregateId + currency.
                o.stringValue("eventType", "AccountCreated")
                o.uuid("aggregateId")
                o.stringType("currency", "CZK")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountCreatedPact")
    fun `the AccountCreated event carries the fields BalanceInitConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        // Mirrors BalanceInitConsumer.consume: filter by eventType, then read aggregateId + currency.
        assertThat(node["eventType"].asText()).isEqualTo("AccountCreated")
        assertThat(UUID.fromString(node["aggregateId"].asText())).isNotNull()
        assertThat(node["currency"].asText()).isNotBlank()
    }
}
