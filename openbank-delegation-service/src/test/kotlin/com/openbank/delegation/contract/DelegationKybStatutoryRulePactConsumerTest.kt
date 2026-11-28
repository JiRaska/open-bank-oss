// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.delegation.infrastructure.client.KybStatutoryRuleRestClient
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-kyb-service", pactVersion = PactSpecVersion.V3)
class DelegationKybStatutoryRulePactConsumerTest {

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-kyb-service")
    fun currentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("delegation may inspect the current statutory representation")
        .uponReceiving("GET the current statutory representation revision as delegation")
        .path(EXPECTED_CURRENT_PATH)
        .query("entityPartyId=$ENTITY")
        .method("GET")
        .willRespondWith()
        .status(204)
        .headers(mapOf("Cache-Control" to "no-store"))
        .toPact()

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-kyb-service")
    fun stalePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("delegation may inspect a stale statutory representation")
        .uponReceiving("GET a stale statutory representation revision as delegation")
        .path(EXPECTED_STALE_PATH)
        .query("entityPartyId=$ENTITY")
        .method("GET")
        .willRespondWith()
        .status(404)
        .headers(mapOf("Cache-Control" to "no-store"))
        .toPact()

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-kyb-service")
    fun deniedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the shared backend identity attempts to inspect the current statutory representation")
        .uponReceiving("GET the current statutory representation revision as an unapproved workload")
        .path(EXPECTED_CURRENT_PATH)
        .query("entityPartyId=$ENTITY")
        .method("GET")
        .willRespondWith()
        .status(403)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "currentPact")
    fun `the named client can read the current revision`(server: MockServer) {
        assertThat(KybStatutoryRuleRestClient::class.java.getAnnotation(OidcClientFilter::class.java).value)
            .isEqualTo("m2m")
        read(server, CURRENT_REVISION).statusCode(204).header("Cache-Control", "no-store")
    }

    @Test
    @PactTestFor(pactMethod = "stalePact")
    fun `the stale revision is unavailable`(server: MockServer) {
        read(server, STALE_REVISION).statusCode(404).header("Cache-Control", "no-store")
    }

    @Test
    @PactTestFor(pactMethod = "deniedPact")
    fun `the unapproved workload cannot read the same revision`(server: MockServer) {
        read(server, CURRENT_REVISION).statusCode(403)
    }

    private fun read(server: MockServer, revision: String): ValidatableResponse {
        val client = KybStatutoryRuleRestClient::class.java
        val query = client.methods.single { it.name == "verify" }.parameters
            .mapNotNull { it.getAnnotation(QueryParam::class.java)?.value }.single()
        return given().baseUri(server.getUrl()).queryParam(query, ENTITY)
            .get(ClientRoute.of(client, "verify", "id" to CASE, "attestationId" to revision)).then()
    }

    private companion object {
        const val CASE = "b7300000-0000-4000-8000-000000000001"
        const val CURRENT_REVISION = "b7300000-0000-4000-8000-000000000002"
        const val STALE_REVISION = "b7300000-0000-4000-8000-000000000003"
        const val ENTITY = "b7300000-0000-4000-8000-000000000004"
        const val EXPECTED_CURRENT_PATH = "/api/v1/kyb/cases/$CASE/statutory-representation/$CURRENT_REVISION/current"
        const val EXPECTED_STALE_PATH = "/api/v1/kyb/cases/$CASE/statutory-representation/$STALE_REVISION/current"
    }
}
