// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.analytics.infrastructure.reconcile.AccountRegistryClient
import com.openbank.analytics.infrastructure.reconcile.AccountRegistryPage
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for analytics-sink's account discovery: the initial-load source pages
 * `GET /api/v1/accounts/active` to snapshot the fleet's account registry into its reconcile
 * projection (ADR-0143 added the endpoint for billing; analytics reuses it instead of reading
 * account-service's database — see AccountRegistryClient's KDoc).
 *
 * Why it matters: [AccountRegistryPage.data] defaults to an empty list and
 * [com.openbank.analytics.infrastructure.reconcile.AccountRegistryPagination] defaults
 * `nextCursor` to null, so a renamed `data` does not fail loudly — the initial load simply
 * projects zero accounts and every downstream reconcile row is missing, quietly. The row is
 * pinned non-trivially: it binds `id`, `accountNumber`, `accountType`, `partyId`, `productId`,
 * `currencyCode` and `openedAt`, and the pagination object must be present.
 *
 * `nextCursor` is deliberately NOT pinned here. The provider state seeds a single account, so the
 * page is the last one and account-service omits the cursor (`nextCursor` is null when
 * `hasNextPage` is false, and the DTO is `NON_NULL`). Requiring it made the provider replay fail
 * on every run and reddened main. Pinning the cursor needs a provider state with more rows than
 * the page limit — a separate interaction, not this one.
 *
 * Reuses account-service's existing provider state ("an account owned by a known party exists")
 * so AccountPactFolderProviderVerificationTest serves the 200 leg without provider-side changes;
 * the refusal leg reuses the fleet NEGATIVE_AUTH_STATE served by
 * AccountNegativeAuthProviderVerificationTest (ADR-0279 #3).
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountRegistryPactConsumerTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeRegistryPagePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the first page of ACTIVE accounts for the analytics initial load")
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
                    a.stringType("accountNumber", "0001-0000000001/0100")
                    a.stringType("accountType", "CURRENT")
                    a.uuid("partyId")
                    a.stringType("productId", "current-czk")
                    a.stringType("currencyCode", "CZK")
                    a.stringType("openedAt", "2026-01-15T00:00:00Z")
                }
                o.`object`("pagination") { p ->
                    p.booleanType("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `AccountNegativeAuthProviderVerificationTest`, which boots the
     * provider without a test identity; the positive twin filters this state out because its
     * class-level `@TestSecurity` would authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeRegistryPageUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the ACTIVE accounts page with no M2M identity is refused")
        .path(EXPECTED_ACTIVE_PATH)
        .method("GET")
        .query("limit=$PAGE_LIMIT")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeRegistryPageUnauthenticatedPact")
    fun `a registry page with no identity is refused with 401, never listed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientDerivedActivePath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "activeRegistryPagePact")
    fun `a registry page binds with rows and a terminal pagination block`(mockServer: MockServer) {
        assertThat(clientDerivedActivePath())
            .describedAs(
                "AccountRegistryClient's @Path no longer produces the path this pact pins — fix " +
                    "the client or update EXPECTED_ACTIVE_PATH *and* re-verify against account-service",
            )
            .isEqualTo(EXPECTED_ACTIVE_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientDerivedActivePath())
            .then()
            .statusCode(200)
            .extract().asString()

        val page = mapper.readValue<AccountRegistryPage>(raw)
        // Non-empty is the assertion that matters: the DTO's defaults would read a renamed `data`
        // as an empty page — the silent zero-account initial load this contract exists for.
        assertThat(page.data).isNotEmpty()
        val row = page.data.first()
        assertThat(row.accountNumber).isNotBlank()
        assertThat(row.currencyCode).isEqualTo("CZK")
        // A single-row provider state is the last page: hasNextPage=false and no cursor, which is
        // exactly what the real provider answers (see the class KDoc).
        assertThat(page.pagination).isNotNull()
        assertThat(page.pagination?.nextCursor).isNull()
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-analytics-sink"
        const val PROVIDER = "openbank-account-service"

        /** What the initial load asks for when it passes no limit (the client's default). */
        const val PAGE_LIMIT = 100

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_ACTIVE_PATH = "/api/v1/accounts/active"

        fun clientDerivedActivePath(): String {
            val base = AccountRegistryClient::class.java.getAnnotation(Path::class.java).value
            val method = AccountRegistryClient::class.java.methods
                .single { it.name == "listActive" }
                .getAnnotation(Path::class.java).value
            return base + method
        }
    }
}
