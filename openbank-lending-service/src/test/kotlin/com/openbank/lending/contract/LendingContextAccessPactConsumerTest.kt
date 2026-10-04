// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.lending.infrastructure.client.ContextAssignedCandidates
import com.openbank.lending.infrastructure.client.ContextLendingGraphAccessClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Pins the separate assignment decision and bounded candidate response consumed by Lending. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-context-service", pactVersion = PactSpecVersion.V3)
class LendingContextAccessPactConsumerTest {
    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun assignedLoanAccess(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a lending investigator is assigned to the requested loan")
        .uponReceiving("GET data-free access for an assigned lending loan")
        .path(ACCESS_PATH)
        .method("GET")
        .headers(headers())
        .willRespondWith()
        .status(204)
        .headers(mapOf("Cache-Control" to "no-store"))
        .toPact()

    @Test
    @PactTestFor(pactMethod = "assignedLoanAccess")
    fun `assigned loan access returns no data`(mockServer: MockServer) {
        assertClientRoute("check", ACCESS_PATH)
        request(mockServer).get(ACCESS_PATH).then().statusCode(204)
            .header("Cache-Control", "no-store")
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun assignedCandidates(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a lending investigator is assigned to the requested loan")
        .uponReceiving("GET assigned lending loan candidates without a shared match")
        .path(CANDIDATES_PATH)
        .method("GET")
        .headers(headers())
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"))
        .body("""{"ids":[],"truncated":false}""")
        .toPact()

    @Test
    @PactTestFor(pactMethod = "assignedCandidates")
    fun `candidate response binds into the client DTO`(mockServer: MockServer) {
        assertClientRoute("assignedCandidates", CANDIDATES_PATH)
        val body = request(mockServer).get(CANDIDATES_PATH).then().statusCode(200).extract().asString()
        val candidates = mapper.readValue<ContextAssignedCandidates>(body)
        assertThat(candidates.ids).isEmpty()
        assertThat(candidates.truncated).isFalse()
    }

    private fun request(server: MockServer) = given().baseUri(server.getUrl())
        .header("X-Investigation-Case-Id", LOAN_ID)
        .header("X-Investigation-Purpose", PURPOSE)

    private fun headers() = mapOf(
        "X-Investigation-Case-Id" to LOAN_ID,
        "X-Investigation-Purpose" to PURPOSE,
    )

    private fun assertClientRoute(methodName: String, expected: String) {
        val client = ContextLendingGraphAccessClient::class.java
        val base = requireNotNull(client.getAnnotation(Path::class.java)).value
        val method = client.methods.single { it.name == methodName }
        val path = requireNotNull(method.getAnnotation(Path::class.java)).value
        assertThat(base + path.replace("{loanId}", LOAN_ID)).isEqualTo(expected)
        assertThat(
            method.parameterAnnotations.flatMap { it.asList() }
                .filterIsInstance<HeaderParam>().map { it.value },
        ).contains("Authorization")
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-context-service"
        const val LOAN_ID = "f0c8d9e0-0000-4000-8000-000000000003"
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
        const val ACCESS_PATH = "/api/v1/context/lending-loans/$LOAN_ID/access"
        const val CANDIDATES_PATH = "/api/v1/context/lending-loans/$LOAN_ID/assigned-candidates"
    }
}
