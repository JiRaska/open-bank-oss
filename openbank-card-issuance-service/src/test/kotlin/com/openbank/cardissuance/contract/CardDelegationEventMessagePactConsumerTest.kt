// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.contract

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
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Consumer-driven MESSAGE contract for the CARD-scoped `openbank.delegation.events` payloads
 * ([com.openbank.cardissuance.infrastructure.kafka.CardDelegationEventConsumer], ADR-0232 D3) —
 * issue #2991.
 *
 * A near-twin of account-service's `DelegationEventMessagePactConsumerTest`, and deliberately its
 * own pact rather than a shared one: the two consumers filter on DIFFERENT `resourceType` values
 * (`"CARD"` here, `"ACCOUNT"`/`"SAVINGS_GOAL"` there) and card-issuance reads no
 * `perTransactionLimit` at all, so a single shared contract would have to be the union — which
 * would assert against card-issuance a field it does not consume, and stop asserting the one
 * discriminator that decides whether it consumes anything.
 *
 * `resourceType` is pinned to `"CARD"` as a `stringValue` for exactly that reason: `dispatch`
 * drops any event whose `resourceType != "CARD"`, silently and by design. If delegation-service
 * ever emitted `"PAYMENT_CARD"`, this projection would go permanently empty with no error
 * anywhere, and only this matcher would say so.
 *
 * Provider replay: `DelegationEventPactFolderProviderVerificationTest` in
 * openbank-delegation-service (`@PactFolder`, runs on every PR).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-delegation-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class CardDelegationEventMessagePactConsumerTest {

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "openbank-card-issuance-service", provider = "openbank-delegation-service")
    fun cardDelegationActivatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a CARD-scoped delegation grant has been activated")
        .expectsToReceive("a DelegationActivated event for a card")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationActivated")
                o.uuid("aggregateId")
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "CARD")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("CARD_VIEW") }
                o.datetime("validFrom", "yyyy-MM-dd'T'HH:mm:ssXXX")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardDelegationActivatedPact")
    fun `DelegationActivated carries every field the card projection upsert needs`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("DelegationActivated")
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("grantorPartyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("granteePartyId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("CARD")
        // resourceId IS the card id here — `upsert` returns without writing anything when it is
        // absent, so an unreadable resourceId is a projection that silently never fills.
        assertThat(UUID.fromString(node.path("resourceId").asText())).isNotNull()
        assertThat(node.path("capabilities").isArray).isTrue()
        assertThat(node.path("capabilities")).isNotEmpty()
        assertThat(OffsetDateTime.parse(node.path("validFrom").asText())).isNotNull()
    }

    @Pact(consumer = "openbank-card-issuance-service", provider = "openbank-delegation-service")
    fun cardDelegationRevokedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a CARD-scoped delegation grant has been revoked")
        .expectsToReceive("a DelegationRevoked event for a card")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationRevoked")
                o.uuid("aggregateId")
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "CARD")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("CARD_VIEW") }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardDelegationRevokedPact")
    fun `DelegationRevoked identifies the card grant to close`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("DelegationRevoked")
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("CARD")
    }
}
