// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.lending.infrastructure.client.CreditProfileClient
import com.openbank.lending.infrastructure.client.CreditProfileResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import jakarta.ws.rs.Path as JaxrsPath

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-analytics-sink", pactVersion = PactSpecVersion.V3)
class AnalyticsCreditProfilePactConsumerTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val client = HttpClient.newHttpClient()

    @Pact(consumer = "openbank-lending-service", provider = "openbank-analytics-sink")
    fun creditProfile(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a synthetic party has four observed months")
        .uponReceiving("GET observed monthly cash flows for lending")
        .path("$CREDIT_PROFILE/$OBSERVED_PARTY")
        .method("GET").headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(
            """
            {"months_observed":4,"income_monthly":23456.78,"outflow_monthly":13456.78,
             "net_monthly":10000,"volatility_ratio":0.125}
            """.trimIndent(),
        )
        .given("a synthetic party has no observed months")
        .uponReceiving("GET an unobserved credit profile for lending")
        .path("$CREDIT_PROFILE/$UNOBSERVED_PARTY")
        .method("GET").headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(
            """
            {"months_observed":0,"income_monthly":null,"outflow_monthly":null,
             "net_monthly":null,"volatility_ratio":null}
            """.trimIndent(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "creditProfile")
    fun `lending decodes observed amounts and distinguishes no history`(server: MockServer) {
        assertThat(profile(server, OBSERVED_PARTY))
            .isEqualTo(CreditProfileResponse(4, "23456.78", "13456.78", "10000", "0.125"))
        assertThat(profile(server, UNOBSERVED_PARTY))
            .isEqualTo(CreditProfileResponse(0, null, null, null, null))
    }

    private fun profile(server: MockServer, partyId: String): CreditProfileResponse {
        val contract = CreditProfileClient::class.java
        val resource = contract.getAnnotation(JaxrsPath::class.java).value
        val operation = contract.getMethod("profile", UUID::class.java).getAnnotation(JaxrsPath::class.java).value
        val path = (resource + operation).replace("{partyId}", partyId)
        val request = HttpRequest.newBuilder(URI.create(server.getUrl() + path))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        return mapper.readValue(response.body(), CreditProfileResponse::class.java)
    }

    private companion object {
        const val CREDIT_PROFILE = "/api/v1/analytics/credit-profile"
        const val OBSERVED_PARTY = "30000000-0000-0000-0000-000000000101"
        const val UNOBSERVED_PARTY = "30000000-0000-0000-0000-000000000102"
    }
}
