// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.contract

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
 * Consumer-driven MESSAGE contract for the `AccountCreated` event referral-service consumes from
 * `openbank.accounts.account.created` ([com.openbank.referral.infrastructure.kafka.AccountOpenedFactConsumer],
 * ADR-0310 D1).
 *
 * Until this class, referral-service had NO contract test at all — the qualification trigger its
 * whole programme design rests on was described only by prose.
 *
 * What the consumer actually branches on — and what is therefore pinned, not typed:
 * - `eventType` MUST be `stringValue("AccountCreated")`: `QualificationRule.WIRE_EVENT_TO_KEY`
 *   maps exactly that literal to the `account.opened` qualifying key; anything else is ignored
 *   silently. The KDoc on the rule says why a renamed type must not be chased: published
 *   programmes pin their `qualifyingEvent` to the wire name.
 * - `eventId` is read as OPAQUE TEXT (`asText().takeIf { it.isNotBlank() }`), not parsed as a
 *   UUID — it is the idempotency key for `recordAccountOpened`, and a malformed value must be
 *   skipped as unprocessable, not crash the channel. The contract asserts a string; the
 *   consumer's blank-check is exercised by the malformed-payload leg, not by this pact.
 * - `partyId` and `occurredAt` are required: both feed the qualification decision
 *   (`decide` compares `fact.occurredAt` against the invite's issue/expiry window), and either
 *   missing makes the consumer skip the event.
 *
 * A deliberate near-twin of balance-service's `AccountCreatedMessagePactConsumerTest`, and its
 * own pact rather than a shared one for the same reason card-issuance carries its own
 * DelegationEvent pact: the consumers read DIFFERENT fields (balance keys its projection on
 * `aggregateId` + `currency`, referral on `partyId` + `occurredAt` + opaque `eventId`), so one
 * shared contract would assert against each consumer a field it does not consume.
 *
 * Provider replay: account-service's `AccountPactFolderProviderVerificationTest`
 * (`@PactFolder("../pacts")`, always runs on a PR). Its given state "an account has been created"
 * already exists, and its `@PactVerifyProvider` produces the full `AccountCreatedEvent` — which
 * carries every field this contract declares.
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` method changes, re-run it
 * (`./gradlew :openbank-referral-service:test --tests "*AccountCreatedMessagePactConsumerTest*"`)
 * and commit the regenerated `pacts/openbank-referral-service-openbank-account-service.json` in
 * the same PR — `pact-drift-check.yml` fails on an uncommitted `pacts/` diff.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-account-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class AccountCreatedMessagePactConsumerTest {

    private companion object {
        /**
         * The `occurredAt` example, pinned to a UTC instant AND rendered in UTC — see
         * `ReferralQualifiedMessagePactConsumerTest` for why the `(name, format, Date, TimeZone)`
         * overload is the only drift-safe form.
         */
        const val OCCURRED_AT_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
        val OCCURRED_AT_EXAMPLE: Date = Date.from(Instant.parse("2026-01-01T00:00:00Z"))
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "openbank-referral-service", provider = "openbank-account-service")
    fun accountCreatedQualifiesPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an account has been created")
        .expectsToReceive("an AccountCreated event eligible for referral qualification")
        .withContent(
            newJsonBody { o ->
                // The consumer filters on the exact literal and reads eventId as opaque text.
                o.stringValue("eventType", "AccountCreated")
                o.stringType("eventId")
                o.uuid("partyId")
                o.datetime("occurredAt", OCCURRED_AT_FORMAT, OCCURRED_AT_EXAMPLE, UTC)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountCreatedQualifiesPact")
    fun `AccountCreated carries the qualification decision inputs`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        // Mirrors AccountOpenedFactConsumer.consume: literal discriminator, then the three fields
        // whose absence makes the consumer skip the event — guessing them would break the
        // idempotency the design rests on.
        assertThat(node.path("eventType").asText()).isEqualTo("AccountCreated")
        assertThat(node.path("eventId").asText()).isNotBlank()
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(OffsetDateTime.parse(node.path("occurredAt").asText())).isNotNull()
    }
}
