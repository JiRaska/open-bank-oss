// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.interest.infrastructure.client.PartyAmlProfileClientResponse
import com.openbank.interest.infrastructure.client.PartyDetailClientResponse
import com.openbank.interest.infrastructure.client.PartyServiceClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import jakarta.ws.rs.Path as JaxrsPath

/**
 * Consumer contract for the ADR-0339 B tax-profile resolution's party-service legs:
 * `GET /api/v1/parties/{id}` (the party type that decides the taxpayer type) and
 * `GET /api/v1/parties/{id}/aml-profile` (the residency countries).
 *
 * The account→partyId hop lives in [ActiveAccountsPactConsumerTest] — pact.writer.overwrite=true
 * makes each test class rewrite the whole pact file for its consumer→provider pair, so there is
 * exactly one test class per pair.
 *
 * The AML-profile pact body deliberately carries a `tin` map — the real response includes it —
 * while the consumer DTO has no such field: the contract documents that interest-service reads
 * residency countries only and never deserializes TINs (GDPR minimization, ADR-0339 A).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class PartyTaxProfilePactConsumerTest {
    private val mapper = ObjectMapper().findAndRegisterModules().registerKotlinModule()

    // Only what the consumer deserializes is pinned: PartyTaxProfileProvider reads partyType (and
    // nothing else) off the party record — pinning derived facts (fatcaStatus/crsStatus) here would
    // force the provider's state to declare an AML profile for a contract that never reads one.
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun partyDetailPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the party record to decide the taxpayer type")
        .path(PARTY_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(PARTY_ID))
                o.stringValue("partyType", "INDIVIDUAL")
                o.stringValue("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    // declaredAt is pinned by TYPE, not value: the provider seeds the declaration at replay time,
    // so an exact instant could never match. Everything the consumer reads is exact: the residency
    // countries, usPerson, and the derived reviewStatus/fatcaStatus/crsStatus the boundary relies on.
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun amlProfilePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("$STATE with a declared personal AML profile")
        .uponReceiving("GET the AML profile's residency countries for withholding resolution")
        .path(AML_PROFILE_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("partyId", UUID.fromString(PARTY_ID))
                o.integerType("version", 1)
                o.stringType("declaredAt", "2027-01-01T00:00:00Z")
                o.array("taxResidencies") { a ->
                    a.stringValue("CZ")
                    a.stringValue("DE")
                }
                o.`object`("tin") { t -> t.stringValue("DE", "123456789") }
                o.booleanValue("usPerson", false)
                o.booleanValue("truthful", true)
                o.array("riskFactors") {}
                o.booleanValue("eddRequired", false)
                o.stringValue("reviewStatus", "STANDARD")
                o.booleanValue("pepFlag", false)
                o.stringValue("fatcaStatus", "NON_US")
                o.stringValue("crsStatus", "REPORTABLE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyDetailPact")
    fun `party detail exposes the partyType that decides the taxpayer type`(server: MockServer) {
        assertThat(partyPath()).isEqualTo(PARTY_PATH)

        val detail = get(server, PARTY_PATH).let { mapper.readValue(it, PartyDetailClientResponse::class.java) }

        assertThat(detail.id.toString()).isEqualTo(PARTY_ID)
        assertThat(detail.partyType).isEqualTo("INDIVIDUAL")
    }

    @Test
    @PactTestFor(pactMethod = "amlProfilePact")
    fun `aml profile exposes residency countries and the consumer never sees TINs`(server: MockServer) {
        assertThat(amlProfilePath()).isEqualTo(AML_PROFILE_PATH)

        val profile = get(server, AML_PROFILE_PATH).let {
            mapper.readValue(it, PartyAmlProfileClientResponse::class.java)
        }

        assertThat(profile.taxResidencies).containsExactly("CZ", "DE")
        assertThat(profile.usPerson).isFalse()
        // The wire carried a TIN map (see pact body); the DTO has no field for it, so it can
        // never be persisted here — that is the ADR-0339 A boundary, pinned by the contract.
        assertThat(PartyAmlProfileClientResponse::class.java.declaredFields.none { it.name == "tin" }).isTrue()
    }

    // ADR-0279 #3: a contract that only covers success stays green when the provider stops
    // enforcing authz — the residency read carries the 401 refusal too. The providerStates name
    // is what both party provider-verification classes key their test-identity clearing on.
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun amlProfileUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the AML profile with no caller identity is refused")
        .path(AML_PROFILE_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "amlProfileUnauthenticatedPact")
    fun `an aml profile read with no caller identity is refused with 401`(server: MockServer) {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(server.getUrl() + AML_PROFILE_PATH))
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertThat(response.statusCode()).isEqualTo(401)
    }

    private fun get(server: MockServer, path: String): String = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(server.getUrl() + path))
            .header("Accept", "application/json")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    /** The production client's reflected path — a JAX-RS annotation drift fails here, not in prod. */
    private fun partyPath(): String {
        val method = PartyServiceClient::class.java.getMethod("getParty", UUID_CLASS)
        return PartyServiceClient::class.java.getAnnotation(JaxrsPath::class.java).value +
            method.getAnnotation(JaxrsPath::class.java).value.replace("{id}", PARTY_ID)
    }

    private fun amlProfilePath(): String {
        val method = PartyServiceClient::class.java.getMethod("getAmlProfile", UUID_CLASS)
        return PartyServiceClient::class.java.getAnnotation(JaxrsPath::class.java).value +
            method.getAnnotation(JaxrsPath::class.java).value.replace("{id}", PARTY_ID)
    }

    private companion object {
        val UUID_CLASS = java.util.UUID::class.java

        const val CONSUMER = "openbank-interest-service"
        const val PROVIDER = "openbank-party-service"
        const val PARTY_ID = "61000000-0000-0000-0000-000000000061"
        const val STATE = "an INDIVIDUAL party owns the account"
        const val NEGATIVE_AUTH_STATE = "no valid caller identity is presented"
        const val PARTY_PATH = "/api/v1/parties/$PARTY_ID"
        const val AML_PROFILE_PATH = "/api/v1/parties/$PARTY_ID/aml-profile"
    }
}
