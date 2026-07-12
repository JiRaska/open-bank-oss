// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.contract

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
 * Consumer-driven MESSAGE contracts for kyc-domain events onboarding-service consumes
 * ([com.openbank.onboarding.infrastructure.kafka.OnboardingEventConsumer.consumeKycEvent],
 * ADR-0068, issue #468 platform edge — onboarding -> party/kyc/sca). First-ever pact provider
 * role for kyc-service — it was only ever a message CONSUMER before (see the kyc->party edge,
 * `PartyEventMessagePactConsumerTest` in kyc-service).
 *
 * Real bug found and fixed alongside this contract: `KycEventPublisher.publish` always
 * serializes the field as `"status"`, never `"newStatus"`. `OnboardingEventConsumer`'s
 * `KYC_CASE_STATUS_CHANGED` branch only read `"newStatus"`, with a hardcoded fallback covering
 * only the terminal `KYC_CASE_APPROVED`/`KYC_CASE_REJECTED` types (which "worked" by coincidence
 * — they never actually read the payload) — the generic `KYC_CASE_STATUS_CHANGED` type (fired for
 * `UNDER_REVIEW` transitions and PEP-escalation re-scoring, `KycService.kt:212,267`) had no such
 * fallback and was silently dropped every time.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-kyc-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class KycEventMessagePactConsumerTest {

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-kyc-service")
    fun kycCaseOpenedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a KYC case has been opened")
        .expectsToReceive("a KYC_CASE_OPENED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "KYC_CASE_OPENED")
                o.uuid("partyId")
                o.uuid("kycCaseId")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycCaseOpenedPact")
    fun `the KYC_CASE_OPENED event carries the fields OnboardingEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_CASE_OPENED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("kycCaseId").asText())).isNotNull()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-kyc-service")
    fun kycCaseStatusChangedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a KYC case status has changed")
        .expectsToReceive("a KYC_CASE_STATUS_CHANGED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "KYC_CASE_STATUS_CHANGED")
                o.uuid("partyId")
                o.uuid("kycCaseId")
                o.stringType("status", "UNDER_REVIEW")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycCaseStatusChangedPact")
    fun `the KYC_CASE_STATUS_CHANGED event carries the fields OnboardingEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_CASE_STATUS_CHANGED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("kycCaseId").asText())).isNotNull()
        assertThat(node.path("status").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-kyc-service")
    fun kycCaseApprovedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a KYC case has been approved")
        .expectsToReceive("a KYC_CASE_APPROVED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "KYC_CASE_APPROVED")
                o.uuid("partyId")
                o.uuid("kycCaseId")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycCaseApprovedPact")
    fun `the KYC_CASE_APPROVED event carries the fields OnboardingEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_CASE_APPROVED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("kycCaseId").asText())).isNotNull()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-kyc-service")
    fun kycCaseRejectedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("a KYC case has been rejected")
        .expectsToReceive("a KYC_CASE_REJECTED event")
        .withContent(
            newJsonBody { o ->
                o.stringValue("eventType", "KYC_CASE_REJECTED")
                o.uuid("partyId")
                o.uuid("kycCaseId")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycCaseRejectedPact")
    fun `the KYC_CASE_REJECTED event carries the fields OnboardingEventConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_CASE_REJECTED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(UUID.fromString(node.path("kycCaseId").asText())).isNotNull()
    }
}
