// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.infrastructure.client.AccountBalanceClientResponse
import com.openbank.interest.infrastructure.client.AccountServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the **booked-balance read** the accrual run makes per account:
 * `AccountDirectoryAdapter.bookedBalance` calling `GET /api/v1/accounts/{id}/balance` and reading
 * `accountId`, `currentBalance` and `currencyCode` (issue #8345).
 *
 * Every field of [AccountBalanceClientResponse] is nullable with a default, so a renamed
 * `currentBalance` does not fail to bind — it arrives as null, and the run accrues on nothing. The
 * field names are the contract; the provider state seeds the account and a CZK balance for it.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountBalancePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the pact account")
        .uponReceiving("GET the booked balance of an account the accrual run is about to accrue on")
        .path(EXPECTED_BALANCE_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("accountId", UUID.fromString(PACT_ACCOUNT_ID))
                o.decimalType("currentBalance", 0.0)
                o.stringValue("currencyCode", "CZK")
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `AccountNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountBalanceUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET a booked balance with no M2M identity is refused")
        .path(EXPECTED_BALANCE_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountBalanceUnauthenticatedPact")
    fun `a balance read with no identity is refused with 401, never disclosed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedBalancePath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "accountBalancePact")
    fun `the balance binds into AccountBalanceClientResponse with the three fields the run reads`(
        mockServer: MockServer,
    ) {
        assertThat(clientDerivedBalancePath())
            .describedAs("AccountServiceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_BALANCE_PATH)

        val raw = given().baseUri(mockServer.getUrl())
            .get(clientDerivedBalancePath()).then().statusCode(200).extract().asString()

        val balance = mapper.readValue(raw, AccountBalanceClientResponse::class.java)
        assertThat(balance.accountId).isEqualTo(UUID.fromString(PACT_ACCOUNT_ID))
        assertThat(balance.currentBalance).isNotNull()
        assertThat(balance.currencyCode).isEqualTo("CZK")
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-interest-service"
        const val PROVIDER = "openbank-account-service"

        /** AccountPactFolderProviderVerificationTest's ACCOUNT_ID, a CZK CURRENT account. */
        const val PACT_ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_BALANCE_PATH = "/api/v1/accounts/$PACT_ACCOUNT_ID/balance"

        fun clientDerivedBalancePath(): String {
            val base = AccountServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = AccountServiceClient::class.java.methods.single { it.name == "getBalance" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID)
        }
    }
}
