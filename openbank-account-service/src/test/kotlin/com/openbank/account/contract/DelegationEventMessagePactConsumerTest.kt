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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.TimeZone
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

    @Pact(consumer = "openbank-account-service", provider = "openbank-delegation-service")
    fun delegationActivatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an ACCOUNT-scoped delegation grant has been activated")
        .expectsToReceive("a DelegationActivated event for an account")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "DelegationActivated")
                o.uuid("aggregateId")
                o.integerType("lifecycleRevision", 1)
                o.uuid("grantorPartyId")
                o.uuid("granteePartyId")
                o.stringValue("resourceType", "ACCOUNT")
                o.uuid("resourceId")
                o.array("capabilities") { caps -> caps.stringType("ACCOUNT_READ_BALANCES") }
                o.datetime("validFrom", VALID_FROM_FORMAT, VALID_FROM_EXAMPLE, UTC)
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
        assertThat(node.path("lifecycleRevision").asLong()).isEqualTo(1)
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
                o.integerType("lifecycleRevision", 2)
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
        assertThat(node.path("lifecycleRevision").asLong()).isEqualTo(2)
        // closeById(grantId) is keyed on aggregateId alone — if that field ever moved, every
        // revoke would be a no-op and the grant would stay enforceable.
        assertThat(UUID.fromString(node.path("aggregateId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("grantorPartyId").asText())).isNotNull()
        assertThat(node.path("resourceType").asText()).isEqualTo("ACCOUNT")
    }
}
