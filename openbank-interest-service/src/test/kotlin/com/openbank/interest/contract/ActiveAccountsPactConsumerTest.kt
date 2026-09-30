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
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.interest.infrastructure.client.AccountServiceClient
import com.openbank.interest.infrastructure.client.ActiveAccountsClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the **accrual run's account discovery**: interest-service pages
 * `GET /api/v1/accounts/active` to find every account it accrues on (ADR-0143; issue #8345).
 *
 * Why it matters: [ActiveAccountsClientResponse] defaults `data` to an empty list and every field
 * of `pagination` to "no next page", so a renamed `data` or `hasNextPage` does not fail loudly —
 * the run simply finds zero accounts, or stops after the first page, and accrues nothing. That is
 * the quiet failure this contract turns into a red build. Each account row binds `id`,
 * `productId`, `accountType` and `currencyCode` non-null.
 *
 * Deliberately NOT covered here: `GET /api/v1/accounts/{id}/balance`, the client's second method.
 * account-service answers it from a remote balance read, so a provider replay would need that
 * dependency stubbed first; it stays on #8345's list.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class ActiveAccountsPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeAccountsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the first page of ACTIVE accounts for the accrual run")
        .path(EXPECTED_ACTIVE_PATH)
        .method("GET")
        .query("limit=$PAGE_LIMIT")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { a ->
                    a.uuid("id")
                    a.uuid("productId")
                    a.stringType("accountType", "CURRENT")
                    a.stringType("currencyCode", "CZK")
                }
                o.`object`("pagination") { p ->
                    p.booleanType("hasNextPage", false)
                }
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
    fun activeAccountsUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the ACTIVE accounts page with no M2M identity is refused")
        .path(EXPECTED_ACTIVE_PATH)
        .method("GET")
        .query("limit=$PAGE_LIMIT")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeAccountsUnauthenticatedPact")
    fun `a discovery page with no identity is refused with 401, never listed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientDerivedActivePath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "activeAccountsPact")
    fun `a page of active accounts binds with rows and a pagination flag the run can follow`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientDerivedActivePath())
            .then()
            .statusCode(200)
            .extract().asString()

        val page = mapper.readValue<ActiveAccountsClientResponse>(raw)
        // Non-empty is the assertion that matters: the DTO's defaults would read a renamed `data`
        // as an empty page, which is exactly the silent zero-accrual run this contract exists for.
        assertThat(page.data).isNotEmpty()
        assertThat(page.data.first().accountType).isNotBlank()
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedActivePath())
            .describedAs(
                "AccountServiceClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_ACTIVE_PATH *and* re-verify against account-service",
            )
            .isEqualTo(EXPECTED_ACTIVE_PATH)
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-interest-service"
        const val PROVIDER = "openbank-account-service"

        /** The client's @DefaultValue — what an accrual run asks for when it passes no limit. */
        const val PAGE_LIMIT = 100

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_ACTIVE_PATH = "/api/v1/accounts/active"

        fun clientDerivedActivePath(): String {
            val base = AccountServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = AccountServiceClient::class.java.methods
                .single { it.name == "listActive" }
                .getAnnotation(Path::class.java).value
            return base + method
        }
    }
}
