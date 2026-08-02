// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

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
 * Consumer-driven MESSAGE contract for the `openbank.delegation.events` payloads account-service
 * builds its enforcement projection from
 * ([com.openbank.account.infrastructure.kafka.DelegationEventConsumer], ADR-0232 D3) — issue #2991.
 *
 * ## Why this needed a contract and a unit test did not suffice
 *
 * `DelegationEventConsumer.parseEnvelope` reads the payload through `objectMapper.readTree` and
 * `node.path(...)`, which means EVERY field it wants is optional at the type level: a renamed or
 * moved field does not throw, it defaults. Concretely — `capabilities` missing becomes the empty
 * set (a grant that authorises nothing), `resourceType` missing becomes `""` (so the
 * `PROJECTED_RESOURCE_TYPES` filter drops the event and no row is ever written), and
 * `perTransactionLimit.amount` missing becomes a null ceiling, which is the projection's
 * "no limit" reading. All three are silent, and only the last one fails OPEN.
 *
 * Two interactions, the two directions the projection can be wrong in:
 *
 * - `DelegationActivated` — the event that CREATES an enforceable row.
 * - `DelegationRevoked`  — the event that CLOSES one. A revoke that fails to parse leaves a
 *   revoked grant enforceable, which the consumer's own KDoc calls the worst direction this
 *   projection can drift.
 *
 * `eventType` and `resourceType` are `stringValue`: the consumer dispatches on the exact strings
 * (`"DelegationActivated"`, and `resourceType in setOf("ACCOUNT", "SAVINGS_GOAL")`), so a type
 * matcher would verify nothing about the only two fields that decide whether anything happens.
 *
 * Provider replay: `DelegationEventPactFolderProviderVerificationTest` in
 * openbank-delegation-service (`@PactFolder`, runs on every PR), which produces these messages
 * from the real `DelegationActivated`/`DelegationRevoked` domain types — so a field renamed in
 * `DelegationEvents.kt` reddens the replay rather than silently emptying this projection.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-delegation-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class DelegationEventMessagePactConsumerTest {

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "openbank-account-service", provider = "openbank-delegation-service")
    fun delegationActivatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an ACCOUNT-scoped delegation grant has been activated")
        .expectsToReceive("a DelegationActivated event for an account")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationActivated")
                o.uuid("aggregateId")
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "ACCOUNT")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("ACCOUNT_READ_BALANCES") }
                o.datetime("validFrom", "yyyy-MM-dd'T'HH:mm:ssXXX")
                o.`object`("perTransactionLimit") { limit ->
                    limit.decimalType("amount", 1500.00)
                    limit.stringType("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "delegationActivatedPact")
    fun `DelegationActivated carries every field the projection upsert needs`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        // Mirrors parseEnvelope + upsert, at the exact paths the consumer reads.
        assertThat(node.path("eventType").asText()).isEqualTo("DelegationActivated")
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("grantorPartyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("granteePartyId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("ACCOUNT")
        assertThat(UUID.fromString(node.path("resourceId").asText())).isNotNull()
        assertThat(node.path("capabilities").isArray).isTrue()
        assertThat(node.path("capabilities")).isNotEmpty()
        assertThat(OffsetDateTime.parse(node.path("validFrom").asText())).isNotNull()
        // The ceiling is read as two flat leaves under perTransactionLimit — NOT as a Money
        // object. DelegationEvents.EventMoney exists precisely because `Money.currency` would
        // render as {"code":"CZK"} and this read would silently return null.
        assertThat(node.path("perTransactionLimit").path("amount").asText().toBigDecimalOrNull()).isNotNull()
        assertThat(node.path("perTransactionLimit").path("currency").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-account-service", provider = "openbank-delegation-service")
    fun delegationRevokedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an ACCOUNT-scoped delegation grant has been revoked")
        .expectsToReceive("a DelegationRevoked event for an account")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationRevoked")
                o.uuid("aggregateId")
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "ACCOUNT")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("ACCOUNT_READ_BALANCES") }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "delegationRevokedPact")
    fun `DelegationRevoked identifies the grant to close`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("DelegationRevoked")
        // closeById(grantId) is keyed on aggregateId alone — if that field ever moved, every
        // revoke would be a no-op and the grant would stay enforceable.
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("grantorPartyId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("ACCOUNT")
    }
}
