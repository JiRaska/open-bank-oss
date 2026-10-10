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
import com.openbank.pension.infrastructure.identity.AccountOwnership
import com.openbank.pension.infrastructure.identity.AccountRestClient
import com.openbank.pension.infrastructure.identity.OwnershipVerificationDto
import com.openbank.pension.infrastructure.identity.OwnershipVerificationRequestDto
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: pension-service -> account-service `POST /api/v1/accounts/ownership-verifications`
 * (action `account.verifyOwnership`, ADR-0335 D2) for OwnAccountVerificationPort, the beneficiary
 * check and F3's debtor-IBAN check. pension never reads an account: the answer is `{owned, active}`
 * plus `accountId` only when owned. States are the ones account-service already serves for sdd's
 * pact (#12419); replayed by `AccountPactFolderProviderVerificationTest` and the negative-auth twin.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class PensionAccountPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    private fun body(iban: String, party: String) = newJsonBody { o ->
        o.stringValue("iban", iban)
        o.stringValue("partyId", party)
    }.build()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun ownedAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(OWNED_STATE)
        .uponReceiving("POST a pension ownership verification for the owner's active account")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(body(IBAN, OWNER_PARTY_ID))
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
    fun foreignPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(OWNED_STATE)
        .uponReceiving("POST a pension ownership verification for another party's IBAN")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(body(IBAN, OTHER_PARTY_ID))
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

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownIbanPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNKNOWN_STATE)
        .uponReceiving("POST a pension ownership verification for an IBAN the bank does not hold")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(body(UNKNOWN_IBAN, OWNER_PARTY_ID))
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

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a pension ownership verification with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(body(IBAN, OWNER_PARTY_ID))
        .willRespondWith()
        .status(401)
        .toPact()

    private fun post(mockServer: MockServer, iban: String, party: String) = given()
        .baseUri(mockServer.getUrl())
        .contentType(ContentType.JSON)
        .body(mapper.writeValueAsString(OwnershipVerificationRequestDto(iban, UUID.fromString(party))))
        .post(clientPath())

    @Test
    @PactTestFor(pactMethod = "ownedAccountPact")
    fun `the owner's active account is owned`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val raw = post(mockServer, IBAN, OWNER_PARTY_ID).then().statusCode(200).extract().asString()
        val verdict = mapper.readValue(raw, OwnershipVerificationDto::class.java)
        assertThat(AccountOwnership.owns(verdict)).isTrue()
        assertThat(verdict.accountId).isEqualTo(UUID.fromString(ACCOUNT_ID))
    }

    @Test
    @PactTestFor(pactMethod = "foreignPartyPact")
    fun `another party's IBAN is not owned`(mockServer: MockServer) {
        val raw = post(mockServer, IBAN, OTHER_PARTY_ID).then().statusCode(200).extract().asString()
        assertThat(AccountOwnership.owns(mapper.readValue(raw, OwnershipVerificationDto::class.java))).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "unknownIbanPact")
    fun `an unknown IBAN answers exactly like a foreign one`(mockServer: MockServer) {
        val raw = post(mockServer, UNKNOWN_IBAN, OWNER_PARTY_ID).then().statusCode(200).extract().asString()
        assertThat(AccountOwnership.owns(mapper.readValue(raw, OwnershipVerificationDto::class.java))).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `no identity is refused with 401`(mockServer: MockServer) {
        post(mockServer, IBAN, OWNER_PARTY_ID).then().statusCode(401)
    }

    private fun clientPath(): String {
        val base = AccountRestClient::class.java.getAnnotation(Path::class.java).value
        val sub = AccountRestClient::class.java.methods.single {
            it.name == "verifyOwnership"
        }.getAnnotation(Path::class.java).value
        return base + sub
    }

    private companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-account-service"
        const val OWNED_STATE = "an account owned by a known party exists"
        const val UNKNOWN_STATE = "no account exists for the unknown IBAN"

        /** The IBAN / owner / account id account-service's provider states serve (#12419's sdd pact). */
        const val IBAN = "CZ6508000000192000145399"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val OTHER_PARTY_ID = "77777777-8888-4999-8aaa-bbbbbbbbbbbb"
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val UNKNOWN_IBAN = "CZ0708000000000000000099"

        /** LITERAL, retyped from account-service's AccountOwnershipResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/accounts/ownership-verifications"
    }
}
