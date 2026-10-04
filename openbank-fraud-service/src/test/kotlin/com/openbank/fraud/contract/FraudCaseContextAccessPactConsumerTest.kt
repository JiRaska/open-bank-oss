// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.fraud.application.port.out.FraudAssignedCandidates
import com.openbank.fraud.infrastructure.client.FraudCaseContextAccessRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Fraud's live authorization and bounded candidate lookup from Context. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-context-service", pactVersion = PactSpecVersion.V3)
class FraudCaseContextAccessPactConsumerTest {
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun authorizedAccess(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a Fraud investigator is assigned to the requested case")
        .uponReceiving("GET Fraud case access for an assigned investigator")
        .path("$BASE_PATH/$CASE_ID/access")
        .method("GET")
        .headers(headers())
        .willRespondWith()
        .status(204)
        .headers(mapOf("Cache-Control" to "no-store"))
        .toPact()

    @Test
    @PactTestFor(pactMethod = "authorizedAccess")
    fun `assigned investigator can access the exact case`(server: MockServer) {
        assertClientPaths()
        request(server).get("$BASE_PATH/$CASE_ID/access").then().statusCode(204)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun assignedCandidates(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a Fraud investigator is assigned to the requested case")
        .uponReceiving("GET assigned Fraud case candidates")
        .path("$BASE_PATH/$CASE_ID/assigned-candidates")
        .method("GET")
        .headers(headers())
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"))
        .body("""{"ids":[],"truncated":false}""")
        .toPact()

    @Test
    @PactTestFor(pactMethod = "assignedCandidates")
    fun `candidate response binds to the fraud client DTO`(server: MockServer) {
        assertClientPaths()
        val body = request(server).get("$BASE_PATH/$CASE_ID/assigned-candidates")
            .then().statusCode(200).extract().asString()
        val candidates = jacksonObjectMapper().readValue<FraudAssignedCandidates>(body)
        assertThat(candidates.ids).isEmpty()
        assertThat(candidates.truncated).isFalse()
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unassignedAccess(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a Fraud investigator is not assigned to the requested case")
        .uponReceiving("GET Fraud case access without an assignment")
        .path("$BASE_PATH/$CASE_ID/access")
        .method("GET")
        .headers(headers())
        .willRespondWith()
        .status(403)
        .headers(mapOf("Cache-Control" to "no-store"))
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unassignedAccess")
    fun `unassigned investigator is denied`(server: MockServer) {
        assertClientPaths()
        request(server).get("$BASE_PATH/$CASE_ID/access").then().statusCode(403)
    }

    private fun request(server: MockServer) = given()
        .baseUri(server.getUrl())
        .headers(headers())

    private fun headers() = mapOf(
        "Authorization" to "Bearer pact-token",
        "X-Investigation-Case-Id" to CASE_ID,
        "X-Investigation-Purpose" to "FRAUD_INVESTIGATION",
    )

    private fun assertClientPaths() {
        val client = FraudCaseContextAccessRestClient::class.java
        assertThat(client.getAnnotation(Path::class.java).value).isEqualTo(BASE_PATH)
        assertThat(client.methods.first { it.name == "check" }.getAnnotation(Path::class.java).value)
            .isEqualTo("/{caseId}/access")
        assertThat(client.methods.first { it.name == "assignedCandidates" }.getAnnotation(Path::class.java).value)
            .isEqualTo("/{caseId}/assigned-candidates")
    }

    private companion object {
        const val CONSUMER = "openbank-fraud-service"
        const val PROVIDER = "openbank-context-service"
        const val BASE_PATH = "/api/v1/context/fraud-cases"
        const val CASE_ID = "f0c8d9e0-0000-4000-8000-000000000004"
    }
}
