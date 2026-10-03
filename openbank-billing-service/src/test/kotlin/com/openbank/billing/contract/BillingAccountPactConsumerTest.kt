// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.billing.infrastructure.client.AccountDto
import com.openbank.billing.infrastructure.client.AccountPageDto
import com.openbank.billing.infrastructure.client.AccountRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contracts for billing's two account reads (issue #8345): the per-account product
 * lookup a fee is priced from (`GET /api/v1/accounts/{id}`, `RestAccountContextPort`) and the
 * fleet-wide sweep discovery (`GET /api/v1/accounts/active`, `RestBillableAccountDiscoveryPort`,
 * ADR-0143).
 *
 * [AccountDto] binds `id`, `productId` and `currencyCode` non-null with no defaults, so a rename
 * fails to construct — and the context port's `runCatching` would turn that into "account unknown,
 * skip billing" on every account, silently. [AccountPageDto] defaults `data` to empty and
 * `hasNextPage` to false, so a renamed field there would bill zero accounts; the pact requires at
 * least one row and the pagination flag.
 *
 * The provider state is the one account-service already seeds for delegation's ownership pact.
 * Expected paths are LITERALS; only the outgoing requests are reflected off the client's `@Path`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class BillingAccountPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the account a fee is priced against")
        .path(EXPECTED_ACCOUNT_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", PACT_ACCOUNT_ID)
                o.stringValue("productId", PACT_PRODUCT_ID)
                o.stringValue("currencyCode", "CZK")
                o.stringType("status", "ACTIVE")
                o.stringValue("partyId", PACT_PARTY_ID)
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeAccountsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the first page of ACTIVE accounts for the billing sweep")
        .path(EXPECTED_ACTIVE_PATH)
        .query("limit=$PAGE_LIMIT")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { a ->
                    a.uuid("id")
                    a.uuid("productId")
                    a.stringType("currencyCode", "CZK")
                }
                o.`object`("pagination") { p -> p.booleanType("hasNextPage", false) }
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
    fun accountUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET an account for fee pricing with no M2M identity is refused")
        .path(EXPECTED_ACCOUNT_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountUnauthenticatedPact")
    fun `an account read with no identity is refused with 401, never priced`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedPath("getAccount", "{id}" to PACT_ACCOUNT_ID))
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "accountPact")
    fun `the account binds into AccountDto with the product a fee is priced from`(mockServer: MockServer) {
        assertThat(clientDerivedPath("getAccount", "{id}" to PACT_ACCOUNT_ID)).isEqualTo(EXPECTED_ACCOUNT_PATH)
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientDerivedPath("getAccount", "{id}" to PACT_ACCOUNT_ID)).then().statusCode(200).extract().asString()
        val account = mapper.readValue<AccountDto>(raw)
        assertThat(account.productId).isEqualTo(PACT_PRODUCT_ID)
        assertThat(account.currencyCode).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "activeAccountsPact")
    fun `a page of active accounts binds with rows and the pagination flag the sweep follows`(mockServer: MockServer) {
        assertThat(clientDerivedPath("listActiveAccounts")).isEqualTo(EXPECTED_ACTIVE_PATH)
        val raw = given().baseUri(mockServer.getUrl()).queryParam("limit", PAGE_LIMIT)
            .get(clientDerivedPath("listActiveAccounts")).then().statusCode(200).extract().asString()
        val page = mapper.readValue<AccountPageDto>(raw)
        assertThat(page.data).isNotEmpty()
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-billing-service"
        const val PROVIDER = "openbank-account-service"
        const val STATE = "an account owned by a known party exists"

        /** AccountPactFolderProviderVerificationTest's seeded account. */
        const val PACT_ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PACT_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val PACT_PRODUCT_ID = "99999999-8888-4777-8666-555555555555"
        const val PAGE_LIMIT = 100

        /** LITERALS, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_ACCOUNT_PATH = "/api/v1/accounts/$PACT_ACCOUNT_ID"
        const val EXPECTED_ACTIVE_PATH = "/api/v1/accounts/active"

        fun clientDerivedPath(method: String, vararg subst: Pair<String, String>): String {
            val base = AccountRestClient::class.java.getAnnotation(Path::class.java).value
            val sub = AccountRestClient::class.java.methods.single { it.name == method }
                .getAnnotation(Path::class.java).value
            return subst.fold(base + sub) { acc, (k, v) -> acc.replace(k, v) }
        }
    }
}
