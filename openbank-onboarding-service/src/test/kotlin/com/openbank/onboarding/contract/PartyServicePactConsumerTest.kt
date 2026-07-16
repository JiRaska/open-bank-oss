// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contracts for everything onboarding-service consumes/calls on
 * openbank-party-service, ADR-0068, issue #468 platform edge — onboarding -> party/kyc/sca:
 * one REST interaction ([com.openbank.onboarding.infrastructure.client.PartyServiceClient])
 * and three Kafka message interactions
 * ([com.openbank.onboarding.infrastructure.kafka.OnboardingEventConsumer.consumePartyEvent]).
 *
 * Both interaction kinds against the SAME provider are deliberately in ONE test class, not two:
 * pact-jvm's `PactConsumerTestExt` writes each test class's pact incrementally, merging with
 * whatever the target `<consumer>-<provider>.json` file already contains — and its V4Pact merge
 * logic doesn't reliably reconcile a `SynchronousHttp` interaction against an
 * `AsynchronousMessage` one written by a SEPARATE class in a SEPARATE `afterAll` pass
 * ("Cannot merge pacts as there were N conflict(s)..."), even though V4Pact itself supports
 * holding both kinds of interaction (confirmed: within ONE class, writing all four interactions
 * in a single `afterAll` pass works cleanly — no cross-class incremental merge involved). Uses V4
 * pact spec because V3 can't hold mixed HTTP+message interactions in one file at all.
 *
 * — REST —
 * `AbandonedRegistrationCleaner`'s daily sweep is the only caller:
 * `PUT /api/v1/parties/{id}/kyc-status` with `{"kycStatus":"EXPIRED"}` to suspend a stuck
 * registration. Real bug found and fixed alongside this contract: `PartyService.deriveStatus`
 * didn't map `KycStatus.EXPIRED` to `PartyStatus.SUSPENDED` at all — an abandoned registration
 * silently reverted to PENDING_KYC instead of the documented "system expiry... party -> SUSPENDED"
 * behavior the cleanup job's own doc comment promises. `AbandonedRegistrationCleanerTest` mocks
 * `PartyServiceClient` entirely and only asserts the call was made, never that party-service
 * actually ends up SUSPENDED — same blind-spot pattern as every prior edge in this sweep.
 *
 * — Kafka —
 * Real bug found and fixed alongside this contract: `KafkaPartyEventPublisher` actually publishes
 * `eventType: "KYC_STATUS_CHANGED"` (see `publishKycStatusChanged`), but
 * `OnboardingEventConsumer.parsePartyEvent`'s `when` only matched `"PARTY_STATUS_CHANGED"` /
 * `"KYC_STATUS_UPDATED"` — a type string that was never actually published. Every KYC/AML status
 * transition from party-service was silently dropped; the onboarding funnel's
 * `PENDING_KYC`/`ACTIVE`/`SUSPENDED` transitions from this leg never fired.
 * `OnboardingEventConsumerTest` only had coverage for `PARTY_CREATED`/`PARTY_ERASED` before this.
 * Deliberately narrower than the fields party-service's real envelope carries, matching
 * kyc-service's sibling contract for the same topic: only the fields `OnboardingEventConsumer`
 * actually reads. `PARTY_UPDATED` is NOT covered — onboarding's parser doesn't match that type at
 * all (by design; it only tracks status-affecting transitions).
 *
 * party-service verifies via `PartyEventPactProviderVerificationTest`
 * (`@PactBroker`, extended with an HTTP target for the REST interaction alongside its
 * existing message-only ones — CI-only, skips locally without `pactbroker.url`). `"a party has
 * been created"` and `"a party KYC status has changed"` are its existing message states, reused
 * as-is; `"a party has been erased"` is new
 * here (also being added independently by the kyc->party edge's PR — expect a trivial rebase
 * conflict on that one state block, not a semantic drift, whichever PR lands second).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V4)
class PartyServicePactConsumerTest {

    private val objectMapper = ObjectMapper()

    private val partyId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val suspendRequestBody = """{"kycStatus":"EXPIRED"}"""

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-party-service")
    fun suspendPartyPact(builder: PactBuilder): V4Pact = builder
        .given("a party exists and can be suspended for KYC expiry")
        .expectsToReceiveHttpInteraction("PUT party kyc-status to EXPIRED (abandoned registration cleanup)") { i ->
            i.withRequest { req ->
                req.method("PUT")
                req.path("/api/v1/parties/$partyId/kyc-status")
                req.header("Content-Type", "application/json")
                req.body(suspendRequestBody)
            }
            i.willRespondWith { resp ->
                resp.status(200)
                resp.header("Content-Type", "application/json")
                resp.body(
                    newJsonBody { o ->
                        o.uuid("id")
                        o.stringType("status", "SUSPENDED")
                        o.stringType("kycStatus", "EXPIRED")
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "suspendPartyPact")
    fun `suspendParty returns the party with status SUSPENDED`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(suspendRequestBody)
            .put("/api/v1/parties/$partyId/kyc-status")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(UUID.fromString(body.getString("id"))).isNotNull()
        assertThat(body.getString("status")).isNotBlank()
        assertThat(body.getString("kycStatus")).isNotBlank()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-party-service")
    fun partyCreatedPact(builder: PactBuilder): V4Pact = builder
        .given("a party has been created")
        .expectsToReceiveMessageInteraction("a PARTY_CREATED event") { i ->
            i.withContents { c ->
                c.withContent(
                    newJsonBody { o ->
                        o.stringValue("eventType", "PARTY_CREATED")
                        o.uuid("partyId")
                        o.stringType("legalName", "Jane Smith")
                        o.stringType("email", "jane.smith@example.com")
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyCreatedPact", providerType = ProviderType.ASYNCH)
    fun `the PARTY_CREATED event carries the fields OnboardingEventConsumer needs`(
        messages: List<V4Interaction.AsynchronousMessage>,
    ) {
        val node = objectMapper.readTree(messages.first().messageContents.valueAsString())

        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_CREATED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("legalName").asText()).isNotBlank()
        assertThat(node.path("email").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-party-service")
    fun kycStatusChangedPact(builder: PactBuilder): V4Pact = builder
        .given("a party KYC status has changed")
        .expectsToReceiveMessageInteraction("a KYC_STATUS_CHANGED event") { i ->
            i.withContents { c ->
                c.withContent(
                    newJsonBody { o ->
                        o.stringValue("eventType", "KYC_STATUS_CHANGED")
                        o.uuid("partyId")
                        o.stringType("status", "ACTIVE")
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "kycStatusChangedPact", providerType = ProviderType.ASYNCH)
    fun `the KYC_STATUS_CHANGED event carries the fields OnboardingEventConsumer needs`(
        messages: List<V4Interaction.AsynchronousMessage>,
    ) {
        val node = objectMapper.readTree(messages.first().messageContents.valueAsString())

        assertThat(node.path("eventType").asText()).isEqualTo("KYC_STATUS_CHANGED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("status").asText()).isNotBlank()
    }

    @Pact(consumer = "openbank-onboarding-service", provider = "openbank-party-service")
    fun partyErasedPact(builder: PactBuilder): V4Pact = builder
        .given("a party has been erased")
        .expectsToReceiveMessageInteraction("a PARTY_ERASED event") { i ->
            i.withContents { c ->
                c.withContent(
                    newJsonBody { o ->
                        o.stringValue("eventType", "PARTY_ERASED")
                        o.uuid("partyId")
                    }.build(),
                )
            }
        }
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyErasedPact", providerType = ProviderType.ASYNCH)
    fun `the PARTY_ERASED event carries the fields OnboardingEventConsumer needs`(
        messages: List<V4Interaction.AsynchronousMessage>,
    ) {
        val node = objectMapper.readTree(messages.first().messageContents.valueAsString())

        assertThat(node.path("eventType").asText()).isEqualTo("PARTY_ERASED")
        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
    }
}
