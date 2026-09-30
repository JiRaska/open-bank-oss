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
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.lending.infrastructure.client.AccountPage
import com.openbank.lending.infrastructure.client.AccountServiceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the **borrower account lookup** a disbursement depends on:
 * [com.openbank.lending.infrastructure.client.AccountServiceClient.findCurrentAccount] calling
 * `GET /api/v1/accounts?partyId=…&limit=50` and picking the ACTIVE CURRENT account in the loan's
 * currency (issue #8345 — one of the money-path calls that had no contract at all).
 *
 * Why it matters: the four fields the lookup filters on (`id`, `accountType`, `currencyCode`,
 * `status`) are all it reads, and [AccountPage] / `AccountSummary` bind them non-null with no
 * default. A renamed field is not a degraded read — the page fails to construct, the lookup fails,
 * and the disbursement fails loud with the borrower unpaid (#3931). Every unit test in the module
 * mocks `BorrowerAccountLookupPort`, so nothing else observes that binding.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290). The provider replay, `AccountPactFolderProviderVerificationTest`,
 * answers the other half: whether account-service still serves this route with this shape.
 *
 * The provider state is the one account-service already seeds for delegation's ownership pact, so
 * the ids below must match its `ACCOUNT_ID` / `OWNER_PARTY_ID`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class BorrowerAccountLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun borrowerAccountsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the borrower's accounts to find the CURRENT account a loan pays into")
        .path(EXPECTED_ACCOUNTS_PATH)
        .method("GET")
        .query("partyId=$OWNER_PARTY_ID&limit=$LIST_LIMIT")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { a ->
                    a.uuid("id", UUID.fromString(ACCOUNT_ID))
                    // stringValue, not stringType: findCurrentAccount compares these three by
                    // equality, so a provider answering "Current" or "active" pays nobody.
                    a.stringValue("accountType", "CURRENT")
                    a.stringValue("currencyCode", "CZK")
                    a.stringValue("status", "ACTIVE")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "borrowerAccountsPact")
    fun `the borrower's accounts bind into AccountPage and carry the three fields the lookup filters on`(
        mockServer: MockServer,
    ) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .queryParam("partyId", OWNER_PARTY_ID)
            .queryParam("limit", LIST_LIMIT)
            .get(clientDerivedAccountsPath())
            .then()
            .statusCode(200)
            .extract().asString()

        // Binding into the real DTO: a renamed field fails to construct here, not in production.
        val page = mapper.readValue<AccountPage>(raw)
        val current = page.data.firstOrNull {
            it.accountType == "CURRENT" && it.currencyCode == "CZK" && it.status == "ACTIVE"
        }
        assertThat(current?.id).isEqualTo(UUID.fromString(ACCOUNT_ID))
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedAccountsPath())
            .describedAs(
                "AccountServiceRestClient's @Path no longer produces the path this pact pins — fix " +
                    "the client or update EXPECTED_ACCOUNTS_PATH *and* re-verify against account-service",
            )
            .isEqualTo(EXPECTED_ACCOUNTS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-account-service"

        /** Must match AccountPactFolderProviderVerificationTest's ACCOUNT_ID / OWNER_PARTY_ID. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /** AccountServiceClient.LIST_LIMIT — the page size the real lookup asks for. */
        const val LIST_LIMIT = 50

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_ACCOUNTS_PATH = "/api/v1/accounts"

        fun clientDerivedAccountsPath(): String =
            AccountServiceRestClient::class.java.getAnnotation(Path::class.java).value
    }
}
