// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.domestic.infrastructure.client.AccountDto
import com.openbank.domestic.infrastructure.client.AccountServiceRestClient
import com.openbank.domestic.infrastructure.client.toStandingLookup
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract for the one read the admission chain's standing step makes against
 * account-service (ADR-0374 D4): the account lookup, WITH `standing`. The rail used to drop
 * everything but `id` and `partyId`; a provider that stopped sending `standing.mayDebit` would
 * turn every domestic payment into a hold, so the field is pinned here.
 *
 * The expected path is a LITERAL and only the outgoing request is read off the client's `@Path`:
 * deriving both from the annotation would move them together and stay green against a route that
 * does not exist. The mock answers whatever is asked, so a wrong path is caught by the provider
 * replay only: account-service's `AccountPactFolderProviderVerificationTest` (the 200 and 404 legs)
 * and `AccountNegativeAuthProviderVerificationTest` (the 401 leg), both on a pull request.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountStandingPactConsumerTest {

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountWithStandingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the debtor account with its standing before a domestic payment is admitted")
        .path("/api/v1/accounts/$ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Only what the standing step reads. The seeded account is ACTIVE, so it may be debited.
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(OWNER_PARTY_ID))
                o.`object`("standing") { s -> s.booleanValue("mayDebit", true) }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the requested account")
        .uponReceiving("GET the standing of a debtor account that does not exist")
        .path("/api/v1/accounts/$MISSING_ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    /**
     * The refusal half (ADR-0279): with no M2M identity account-service must answer 401, which the
     * standing step reads as "could not find out", never as "may debit".
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the debtor account with its standing with no M2M identity is refused")
        .path("/api/v1/accounts/$ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountWithStandingPact")
    fun `the account lookup carries the standing the admission chain decides on`(mockServer: MockServer) {
        val body = given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(accountPathOnClient(ACCOUNT_ID))
            .then().statusCode(200).extract().asString()

        val lookup = mapper.readValue(body, AccountDto::class.java).toStandingLookup()

        assertThat(lookup)
            .isEqualTo(AccountStandingLookup.Known(UUID.fromString(OWNER_PARTY_ID), mayDebit = true, reason = null))
    }

    @Test
    @PactTestFor(pactMethod = "accountMissingPact")
    fun `an unknown debtor account answers 404`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(accountPathOnClient(MISSING_ACCOUNT_ID))
            .then().statusCode(404)
    }

    @Test
    @PactTestFor(pactMethod = "accountUnauthenticatedPact")
    fun `a lookup with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(accountPathOnClient(ACCOUNT_ID))
            .then().statusCode(401)
    }

    /** The path the REAL client would call, read off its JAX-RS annotations. */
    private fun accountPathOnClient(accountId: String): String {
        val root = AccountServiceRestClient::class.java.getAnnotation(Path::class.java).value
        val method = AccountServiceRestClient::class.java.methods.single { it.name == "getById" }
        return (root + method.getAnnotation(Path::class.java).value).replace("{accountId}", accountId)
    }

    private companion object {
        const val CONSUMER = "openbank-domestic-payment"
        const val PROVIDER = "openbank-account-service"

        /** Must match account-service's `AccountNegativeAuthProviderVerificationTest`. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must match AccountPactFolderProviderVerificationTest's ACCOUNT_ID / OWNER_PARTY_ID. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /** Never seeded on the provider side: the 404 leg leans on that. */
        const val MISSING_ACCOUNT_ID = "7d7d7d7d-0000-4000-8000-7d7d7d7d7d7d"

        // The rail's REST client ignores the fields it does not read; so must this test.
        private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
