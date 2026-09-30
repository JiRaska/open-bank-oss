// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
import com.openbank.lending.infrastructure.client.ConsentCheckResult
import com.openbank.lending.infrastructure.client.LendingConsentServiceClient
import com.openbank.lending.infrastructure.client.RestCreditOffersConsentAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the **credit-offers consent gate**: `RestCreditOffersConsentAdapter`
 * asking consent-service whether the party granted the bank (`openbank`) a `CREDIT_OFFERS` consent
 * before a credit offer is shown (issue #8345).
 *
 * `granted` is pinned by VALUE, not type: it is the whole answer, [ConsentCheckResult] defaults it to
 * `false` on an absent field, and the adapter turns any failure into `null` ("unknown"). A provider
 * that stopped sending the field would therefore deny every offer while every test stayed green;
 * the exact pin is what turns that into a red build.
 *
 * The grantee and scope are the adapter's own constants, referenced rather than retyped, so a pact
 * written against a plausible-looking grantee cannot pin a request this service never makes. The
 * provider state is seeded by `ConsentPactProviderVerificationTest` under the id below.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-consent-service", pactVersion = PactSpecVersion.V3)
class CreditOffersConsentPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeCreditOffersConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ACTIVE CREDIT_OFFERS consent covers the pact lending party")
        .uponReceiving("GET whether the party granted the bank a CREDIT_OFFERS consent")
        .path(EXPECTED_ACTIVE_PATH)
        .query("scope=$SCOPE")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.booleanValue("granted", true) }.build())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeCreditOffersConsentPact")
    fun `hasActiveConsent binds into ConsentCheckResult as granted`(mockServer: MockServer) {
        assertThat(clientDerivedActivePath())
            .describedAs(
                "LendingConsentServiceClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_ACTIVE_PATH *and* re-verify against consent-service",
            )
            .isEqualTo(EXPECTED_ACTIVE_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .queryParam("scope", SCOPE)
            .get(clientDerivedActivePath())
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(mapper.readValue(raw, ConsentCheckResult::class.java).granted).isTrue()
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-consent-service"

        /** Must equal PACT_LENDING_PARTY_ID in consent-service's provider verification. */
        const val PACT_PARTY_ID = "c6c6c6c6-c6c6-4c6c-8c6c-c6c6c6c6c6c6"
        const val GRANTEE = RestCreditOffersConsentAdapter.BANK_GRANTEE
        const val SCOPE = RestCreditOffersConsentAdapter.CREDIT_OFFERS_SCOPE

        /** LITERAL, retyped from consent-service's `ConsentResource` — never derived from the client. */
        const val EXPECTED_ACTIVE_PATH = "/api/v1/consents/party/$PACT_PARTY_ID/grantee/$GRANTEE/active"

        fun clientDerivedActivePath(): String {
            val base = LendingConsentServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = LendingConsentServiceClient::class.java.methods
                .single { it.name == "hasActiveConsent" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{partyId}", PACT_PARTY_ID).replace("{granteeId}", GRANTEE)
        }
    }
}
