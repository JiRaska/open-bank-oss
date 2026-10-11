// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sdd.infrastructure.client.AccountOwnershipClient
import com.openbank.sdd.infrastructure.client.OwnershipVerificationRequestDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * ADR-0335 D6: sdd-service asks account-service whether a scoped initiator's debtor IBAN is an
 * active account of the stated party, and which account it is. Provider states are account-service's
 * existing ones (folder twin + broker twin + negative-auth twin replay them). The expected path is a
 * LITERAL, never derived from the client's @Path, so a client pointing at a route the provider does
 * not serve reddens the provider replay.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountOwnershipPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    private val owned = OwnershipVerificationRequestDto(OWNED_IBAN, UUID.fromString(OWNER_PARTY_ID))
    private val unknown = OwnershipVerificationRequestDto(UNKNOWN_IBAN, UUID.fromString(OWNER_PARTY_ID))

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun ownedActiveAccount(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("POST an ownership verification for the owner's active account")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(owned))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanValue("owned", true)
                o.booleanValue("active", true)
                o.stringValue("accountId", ACCOUNT_ID)
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownIban(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the unknown IBAN")
        .uponReceiving("POST an ownership verification for an IBAN the bank does not hold")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(unknown))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanValue("owned", false)
                o.booleanValue("active", false)
            }.build(),
        )
        .toPact()

    /** ADR-0279 #3: the route must still refuse a caller with no valid identity. */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST an ownership verification with a missing or expired token")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(owned))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "ownedActiveAccount")
    fun `the owner's active account verifies with its account id`(mockServer: MockServer) {
        assertClientPathMatchesContract()
        val body = post(mockServer, owned, 200).extract().jsonPath()
        assertThat(body.getBoolean("owned")).isTrue()
        assertThat(body.getBoolean("active")).isTrue()
        assertThat(body.getString("accountId")).isEqualTo(ACCOUNT_ID)
    }

    @Test
    @PactTestFor(pactMethod = "unknownIban")
    fun `an unknown IBAN verifies as not owned and carries no account id`(mockServer: MockServer) {
        val body = post(mockServer, unknown, 200).extract().jsonPath()
        assertThat(body.getBoolean("owned")).isFalse()
        assertThat(body.getString("accountId")).isNull()
    }

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `refuses a caller with no valid identity`(mockServer: MockServer) {
        post(mockServer, owned, 401)
    }

    private fun post(mockServer: MockServer, request: OwnershipVerificationRequestDto, status: Int) = given()
        .baseUri(mockServer.getUrl())
        .contentType("application/json")
        .body(mapper.writeValueAsString(request))
        .post(clientDerivedPath())
        .then()
        .statusCode(status)

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedPath()).isEqualTo(EXPECTED_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-sdd-service"
        const val PROVIDER = "openbank-account-service"

        // account-service's AccountPactFolderProviderVerificationTest fixture for the
        // "an account owned by a known party exists" state.
        const val OWNED_IBAN = "CZ6508000000192000145399"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val UNKNOWN_IBAN = "CZ6508000000192000145981"

        /** LITERAL, retyped from account-service's AccountOwnershipResource @Path. */
        const val EXPECTED_PATH = "/api/v1/accounts/ownership-verifications"

        fun clientDerivedPath(): String =
            AccountOwnershipClient::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
    }
}
