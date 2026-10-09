// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.pension.infrastructure.identity.AccountDto
import com.openbank.pension.infrastructure.identity.AccountOwnership
import com.openbank.pension.infrastructure.identity.AccountRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: pension-service -> account-service `GET /api/v1/accounts/iban/{iban}`
 * (OwnAccountVerificationPort and the beneficiary check, #12377). Replayed by account-service's
 * `AccountPactFolderProviderVerificationTest` and `AccountNegativeAuthProviderVerificationTest`
 * with states they already serve.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class PensionAccountPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun ownedAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(OWNED_STATE)
        .uponReceiving("GET the account behind a pension payout IBAN")
        .path("$EXPECTED_PATH/$IBAN")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("partyId", OWNER_PARTY_ID)
                o.stringValue("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownIbanPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNKNOWN_STATE)
        .uponReceiving("GET the account behind a pension payout IBAN the bank does not hold")
        .path("$EXPECTED_PATH/$UNKNOWN_IBAN")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET an account by IBAN for pension with no M2M identity is refused")
        .path("$EXPECTED_PATH/$IBAN")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "ownedAccountPact")
    fun `the holder decides ownership`(mockServer: MockServer) {
        assertThat(clientPath(IBAN)).isEqualTo("$EXPECTED_PATH/$IBAN")
        val raw = given().baseUri(mockServer.getUrl()).get(clientPath(IBAN)).then().statusCode(200).extract().asString()
        val account = mapper.readValue(raw, AccountDto::class.java)
        assertThat(AccountOwnership.owns(UUID.fromString(OWNER_PARTY_ID), account)).isTrue()
        assertThat(AccountOwnership.owns(UUID.randomUUID(), account)).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "unknownIbanPact")
    fun `an unknown IBAN is NOT_FOUND`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(clientPath(UNKNOWN_IBAN)).then().statusCode(404)
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(clientPath(IBAN)).then().statusCode(401)
    }

    private fun clientPath(iban: String): String {
        val base = AccountRestClient::class.java.getAnnotation(Path::class.java).value
        val sub = AccountRestClient::class.java.methods.single { it.name == "byIban" }.getAnnotation(Path::class.java).value
        return (base + sub).replace("{iban}", iban)
    }

    private companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-account-service"
        const val OWNED_STATE = "an account owned by a known party exists"
        const val UNKNOWN_STATE = "no account exists for the IBAN"

        /** ACCOUNT_ID's IBAN / OWNER_PARTY_ID in account-service's provider verification. */
        const val IBAN = "CZ6508000000192000145399"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val UNKNOWN_IBAN = "CZ5508000000001234567899"

        /** LITERAL, retyped from account-service's AccountResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/accounts/iban"
    }
}
