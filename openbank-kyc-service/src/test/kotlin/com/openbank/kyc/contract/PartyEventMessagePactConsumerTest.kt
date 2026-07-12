// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.contract

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
 * Consumer-driven MESSAGE contracts for party-domain events kyc-service consumes
 * ([com.openbank.kyc.infrastructure.kafka.PartyEventConsumer], ADR-0068, issue #468 platform
 * edge — kyc -> party). Not a REST edge: kyc-service makes no `@RegisterRestClient` call to
 * party-service at all — the real integration is party-service publishing to
 * `openbank.party.events`, consumed here.
 *
 * Narrower than account-service's sibling contract
 * ([com.openbank.account.contract.PartyCreatedMessagePactConsumerTest]) on purpose: this only
 * asserts the fields [PartyEventConsumer] actually reads (`eventType`, `partyId`, and
 * `legalName` for PARTY_CREATED only) — not every field the real envelope happens to carry.
 * Asserting more than the consumer depends on would fail this contract if party-service ever
 * legitimately dropped a field kyc-service never needed.
 *
 * party-service verifies both via `PartyEventPactProviderVerificationTest`
 * (`@PactFolder("../pacts")` — always runs, no Pact Broker involved). PARTY_CREATED reuses that
 * class's existing `"a party has been created"` state; PARTY_ERASED needed a new one, added
 * alongside this contract.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-party-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class PartyEventMessagePactConsumerTest {

    @Pact(consumer = "openbank-kyc-service", provider = "openbank-party-service")
    fun partyCreatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a party has been created")
        .expectsToReceive("a PARTY_CREATED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "PARTY_CREATED")
                o.uuid("partyId")
                o.stringType("legalName", "Jane Smith")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyCreatedPact")
    fun `the PARTY_CREATED event carries the fields PartyEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_CREATED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("legalName").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-kyc-service", provider = "openbank-party-service")
    fun partyErasedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a party has been erased")
        .expectsToReceive("a PARTY_ERASED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "PARTY_ERASED")
                o.uuid("partyId")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyErasedPact")
    fun `the PARTY_ERASED event carries the fields PartyEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_ERASED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
    }
}
