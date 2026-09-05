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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.TimeZone
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

    private companion object {
        /**
         * The `validFrom` example, pinned to a UTC instant AND rendered in UTC.
         *
         * ## Why this is a matcher with an explicit example, and not a `stringValue`
         *
         * `validFrom` is the one field in this contract no consumer branches on: the projection
         * stores it and compares it to `now` at enforcement time, so what the contract owes is the
         * SHAPE — an offset-datetime string `OffsetDateTime.parse` accepts — never a particular
         * instant. Pinning the value would be a false constraint. Contrast `eventType` and
         * `resourceType`, which ARE `stringValue`: `dispatch` compares them to exact literals, so a
         * type matcher there would verify nothing about the only two fields that decide whether
         * anything happens at all. **Do not "simplify" those into matchers, and do not widen this
         * one into a pinned value — the asymmetry is deliberate.**
         *
         * ## Why the example is passed explicitly, with a TimeZone
         *
         * `datetime(name, format)` alone still emits an example string, generated from pact-jvm's
         * default base date rendered in the JVM's DEFAULT timezone. The matcher was already
         * correct; the EXAMPLE encoded the generating machine's timezone, so a pact generated on
         * CET committed `2000-01-31T14:00:00+01:00` while CI regenerated `2000-01-31T13:00:00Z` —
         * the same instant, a different string, and `pact-drift-check` red forever for everyone.
         * The `(name, format, Date, TimeZone)` overload is the only one that pins the rendering:
         * the `Instant` overload still formats with `TimeZone.getDefault()`.
         *
         * The instant is the one the provider replay actually emits
         * (`DelegationEventPactFolderProviderVerificationTest.VALID_FROM`), so the example is a
         * real payload rather than a library default from the year 2000.
         */
        const val VALID_FROM_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
        val VALID_FROM_EXAMPLE: Date = Date.from(Instant.parse("2026-01-01T00:00:00Z"))
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "openbank-card-issuance-service", provider = "openbank-delegation-service")
    fun cardDelegationActivatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a CARD-scoped delegation grant has been activated")
        .expectsToReceive("a DelegationActivated event for a card")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationActivated")
                o.uuid("aggregateId")
                o.integerType("lifecycleRevision", 1)
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "CARD")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("CARD_VIEW") }
                o.datetime("validFrom", VALID_FROM_FORMAT, VALID_FROM_EXAMPLE, UTC)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardDelegationActivatedPact")
    fun `DelegationActivated carries every field the card projection upsert needs`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("DelegationActivated")
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(node.path("lifecycleRevision").asLong()).isEqualTo(1)
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
                o.integerType("lifecycleRevision", 2)
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
        assertThat(node.path("lifecycleRevision").asLong()).isEqualTo(2)
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("CARD")
    }
}
