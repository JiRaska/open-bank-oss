// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.contract

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
 * Consumer-driven MESSAGE contract for the `Qualified` event loyalty-service consumes from
 * `openbank.referral.qualified.v1` ([com.openbank.loyalty.infrastructure.messaging.ReferralQualifiedConsumer],
 * ADR-0310 D3).
 *
 * Until this class, loyalty-service had NO contract test at all — the only fleet consumer of
 * referral-service's headline event carried no machine-checked description of what it reads.
 *
 * What the consumer actually branches on — and what is therefore pinned, not typed:
 * - `eventType` MUST be `stringValue("Qualified")`: `consume` compares it to the literal and
 *   silently returns on anything else. A type matcher here would verify nothing about the only
 *   field that decides whether anything happens at all.
 * - `referrerPartyId` and `inviteId` are the earn's two inputs: `referrerPartyId` is the party
 *   awarded Lístky and `inviteId` is `correlationEventId`, the key the ledger's
 *   `(party, earn source, correlation event)` unique index makes "one invite earns at most once"
 *   a database property. `consume` SKIPS a payload missing either — a rename here is the
 *   programme silently never awarding, with no error anywhere.
 * - `sourceService` is pinned to `"referral-service"` because it is the audit-attribution value
 *   referral-service documents on its own event (`ReferralEvent.sourceService`).
 *
 * The remaining fields (`eventId`, `occurredAt`, `programId`, `refereePartyId`,
 * `qualificationEventId`) are declared as type matchers so the provider replay proves the whole
 * wire shape of the event, not just the two fields loyalty reads — a provider emitting
 * `occurredAt` in a non-ISO shape would be a lying contract even if loyalty never parses it.
 *
 * Provider replay: `ReferralEventPactFolderProviderVerificationTest` in openbank-referral-service
 * (`@PactFolder`, runs on every PR; the broker twin publishes the verification result).
 *
 * IMPORTANT — regenerate on change: if the `@Pact` method changes, re-run this test
 * (`./gradlew :openbank-loyalty-service:test --tests "*ReferralQualifiedMessagePactConsumerTest*"`)
 * and commit the regenerated `pacts/openbank-loyalty-service-openbank-referral-service.json` in
 * the same PR — `pact-drift-check.yml` regenerates every consumer pact and fails on
 * `git diff -- pacts/`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-referral-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class ReferralQualifiedMessagePactConsumerTest {

    private companion object {
        /**
         * The `occurredAt` example, pinned to a UTC instant AND rendered in UTC — the same
         * drift story as the fleet's older delegation-event consumer pacts document in their
         * `VALID_FROM` constants: the `(name, format, Date, TimeZone)` overload is the only one
         * that pins the rendering — pact-jvm's default example is generated from the JVM's
         * default timezone, so the same pact regenerates with a different string on CI and
         * `pact-drift-check` reddens forever.
         */
        const val OCCURRED_AT_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
        val OCCURRED_AT_EXAMPLE: Date = Date.from(Instant.parse("2026-01-01T00:00:00Z"))
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val objectMapper = ObjectMapper()

    @Pact(consumer = "openbank-loyalty-service", provider = "openbank-referral-service")
    fun qualifiedReferralPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a referral invite has been qualified")
        .expectsToReceive("a Qualified event for the qualified invite")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "Qualified")
                o.uuid("eventId")
                o.datetime("occurredAt", OCCURRED_AT_FORMAT, OCCURRED_AT_EXAMPLE, UTC)
                o.uuid("programId")
                o.uuid("inviteId")
                o.uuid("referrerPartyId")
                o.uuid("refereePartyId")
                o.stringType("qualificationEventId")
                o.stringValue("sourceService", "referral-service")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "qualifiedReferralPact")
    fun `Qualified carries the party and invite the earn idempotency keys on`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        // Mirrors ReferralQualifiedConsumer.consume: the literal discriminator, then the two UUIDs
        // whose absence makes the consumer skip the event entirely.
        assertThat(node.path("eventType").asText()).isEqualTo("Qualified")
        assertThat(UUID.fromString(node.path("referrerPartyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("inviteId").asText())).isNotNull()
    }

    @Test
    @PactTestFor(pactMethod = "qualifiedReferralPact")
    fun `Qualified keeps the envelope fields audit and tracing read`(messages: List<Message>) {
        val node = objectMapper.readTree(messages.first().contentsAsBytes())

        assertThat(UUID.fromString(node.path("eventId").asText())).isNotNull()
        assertThat(OffsetDateTime.parse(node.path("occurredAt").asText())).isNotNull()
        assertThat(node.path("sourceService").asText()).isEqualTo("referral-service")
        assertThat(node.path("qualificationEventId").asText()).isNotBlank()
    }
}
