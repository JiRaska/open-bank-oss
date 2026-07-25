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
import java.util.UUID

/**
 * Consumer-driven MESSAGE contracts for party-domain events that account-service consumes
 * ([com.openbank.account.infrastructure.kafka.PartyEventConsumer], ADR-0063 P2 Batch C).
 *
 * - PARTY_CREATED → open a PENDING_ACTIVATION account (P1 contract)
 * - PARTY_UPDATED / KYC_STATUS_CHANGED → reconcile account status via `status` field (P2 extension)
 *
 * party-service verifies all three via `PartyEventPactProviderVerificationTest`
 * (`@PactBroker` — CI-only, publishes/consumes results against the broker; skips locally
 * without `pactbroker.url`).
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change (new interaction,
 * different matcher, renamed field), re-run this test (`./gradlew :openbank-account-service:test
 * --tests "*PartyCreatedMessagePactConsumerTest*"`) and commit the updated
 * `pacts/openbank-account-service-openbank-party-service.json` in the same PR — an un-regenerated
 * pact file silently verifies the OLD contract on the provider side (this is exactly what had
 * happened here: the committed pact was missing the KYC_STATUS_CHANGED interaction this test
 * already generates — regenerated and recommitted alongside this doc comment).
 *
 * `pact-drift-check.yml` (ADR-0063 Phase 2, issue #468) enforces this now: it regenerates every
 * consumer pact and fails on `git diff -- pacts/`. Note what that gate can and cannot see — its
 * only assertion is the diff, so a module it does not regenerate does not read as *unchecked*, it
 * reads as *passing*. Its scope is therefore DERIVED, by
 * `.github/scripts/derive-pact-drift-scope.sh`, from the `@Pact(consumer = .., provider = ..)`
 * annotations themselves; a consumer test in a new module needs no workflow edit, and a pact
 * nothing regenerates fails the derivation instead of going quietly green.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-party-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class PartyCreatedMessagePactConsumerTest {

    @Pact(consumer = "openbank-account-service", provider = "openbank-party-service")
    fun partyCreatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a party has been created")
        .expectsToReceive("a PARTY_CREATED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "PARTY_CREATED")
                o.uuid("partyId")
                o.stringType("partyType", "INDIVIDUAL")
                o.stringType("legalName", "Jane Smith")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyCreatedPact")
    fun `the PARTY_CREATED event carries the fields PartyEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        // Mirrors PartyEventConsumer: filter eventType, then read partyId + partyType + legalName.
        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_CREATED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("partyType").asText()).isNotBlank()
        assertThat(node.path("legalName").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-account-service", provider = "openbank-party-service")
    fun partyUpdatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a party has been updated")
        .expectsToReceive("a PARTY_UPDATED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "PARTY_UPDATED")
                o.uuid("partyId")
                o.stringType("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyUpdatedPact")
    fun `the PARTY_UPDATED event carries the fields PartyEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_UPDATED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("status").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-account-service", provider = "openbank-party-service")
    fun kycStatusChangedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a party KYC status has changed")
        .expectsToReceive("a KYC_STATUS_CHANGED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "KYC_STATUS_CHANGED")
                o.uuid("partyId")
                o.stringType("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycStatusChangedPact")
    fun `the KYC_STATUS_CHANGED event carries the fields PartyEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_STATUS_CHANGED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("status").asText()).isNotBlank()
    }
}
