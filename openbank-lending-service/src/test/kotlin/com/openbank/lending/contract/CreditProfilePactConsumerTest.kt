// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.lending.infrastructure.client.CreditProfileClient
import com.openbank.lending.infrastructure.client.CreditProfileResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/** The credit-offer gate's real analytics-sink read, replayed against ClickHouse by the provider. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-analytics-sink", pactVersion = PactSpecVersion.V3)
class CreditProfilePactConsumerTest {
    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun observedCreditProfile(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PROFILE_STATE)
        .uponReceiving("GET an observed party credit profile for the lending offer gate")
        .path(EXPECTED_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.integerType("months_observed", 1)
                body.stringMatcher("income_monthly", "^[0-9]+(\\.[0-9]+)?$", "1000.00")
                body.stringMatcher("outflow_monthly", "^[0-9]+(\\.[0-9]+)?$", "400.00")
                body.stringMatcher("net_monthly", "^[0-9]+(\\.[0-9]+)?$", "600.00")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun noM2mIdentity(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET a credit profile without M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "observedCreditProfile")
    fun `observed profile binds to the lending response without losing months or amounts`(server: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(server.getUrl()).get(clientPath()).then().statusCode(200).extract().asString()
        val profile = mapper.readValue(body, CreditProfileResponse::class.java)
        assertThat(profile.monthsObserved).isEqualTo(1)
        assertThat(profile.incomeMonthly).isEqualTo("1000.00")
        assertThat(profile.outflowMonthly).isEqualTo("400.00")
        assertThat(profile.netMonthly).isEqualTo("600.00")
    }

    @Test
    @PactTestFor(pactMethod = "noM2mIdentity")
    fun `missing M2M identity is refused before profile data is read`(server: MockServer) {
        given().baseUri(server.getUrl()).get(clientPath()).then().statusCode(401)
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-analytics-sink"
        const val PROFILE_STATE = "an observed credit profile exists for the pact lending party"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val PARTY_ID = "8a8a8a8a-8a8a-4a8a-8a8a-8a8a8a8a8a8a"
        const val EXPECTED_PATH = "/api/v1/analytics/credit-profile/$PARTY_ID"

        fun clientPath(): String {
            val base = CreditProfileClient::class.java.getAnnotation(Path::class.java).value
            val method = CreditProfileClient::class.java.getMethod("profile", UUID::class.java)
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{partyId}", PARTY_ID)
        }
    }
}
