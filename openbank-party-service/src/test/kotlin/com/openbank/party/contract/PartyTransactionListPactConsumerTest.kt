// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.party.infrastructure.client.TransactionServiceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the GDPR Art. 20 portability export's transaction read
 * (ADR-0204 D2): [com.openbank.party.infrastructure.gdpr.PortabilityAggregationAdapter] calling
 * `GET /api/v1/transactions?accountId=&limit=` through the client GENERATED from
 * transaction-service's own `openapi.yaml` (ADR-0319).
 *
 * Until ADR-0319 party-service had no pact for this route at all, so a wrong path would have been
 * invisible to `check-pact-provider-replay.py`, which can only replay *committed* pacts.
 *
 * ## The asymmetry that makes this falsifiable at the consumer layer
 *
 * **The expected path and query names are LITERALS; only the outgoing request is reflected off the
 * generated client** (CLAUDE.md "Contract tests"). [assertClientRouteMatchesContract] pins the
 * generated annotations to the literals; the provider replay
 * (`TransactionPactFolderProviderVerificationTest`) independently answers whether
 * transaction-service serves the route.
 *
 * The list is empty on purpose: the replay runs against an empty database, so the body pins the
 * envelope (`data` is an array) and not any row. Row mapping is pinned against the provider's
 * literal JSON in `PortabilityAggregationAdapterTest`.
 *
 * The negative half is recorded in the same pact: a caller with no valid M2M identity is refused
 * with 401 (ADR-0279 #3), served by `TransactionNegativeAuthPactVerificationTest`.
 *
 * IMPORTANT - regenerate on change: re-run this test
 * (`./gradlew :openbank-party-service:test --tests "*PartyTransactionListPactConsumerTest*"`) and
 * commit the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails the
 * build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class PartyTransactionListPactConsumerTest {

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listTransactionsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the transaction service is available")
        .uponReceiving("GET the transactions of an account for the portability export")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("GET")
        .query("accountId=$ACCOUNT_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(OK)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.array("data") { } }.build())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listTransactionsPact")
    fun `the generated client's list route returns the data envelope`(mockServer: MockServer) {
        assertClientRouteMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            // Reflected off the generated client, NOT retyped: this is the path the real client issues.
            .queryParam("accountId", ACCOUNT_ID)
            .queryParam("limit", PAGE_SIZE)
            .get(clientDerivedTransactionsPath())
            .then()
            .statusCode(OK)
            .extract().jsonPath()

        assertThat(body.getList<Any>("data")).isEmpty()
    }

    /**
     * ADR-0279 #3: pins that transaction-service answers 401 - not an empty page that would read as
     * "this subject has no transactions" - when party-service's M2M token is missing or expired.
     * The adapter treats 401 as a hard failure for exactly that reason.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("GET the transactions of an account with a missing or expired token")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("GET")
        .query("accountId=$ACCOUNT_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(UNAUTHORIZED)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the list with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientRouteMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .queryParam("accountId", ACCOUNT_ID)
            .queryParam("limit", PAGE_SIZE)
            .get(clientDerivedTransactionsPath())
            .then()
            .statusCode(UNAUTHORIZED)
    }

    private fun assertClientRouteMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs(
                "the generated client's @Path no longer produces the path this pact pins - either the " +
                    "spec moved (re-verify against transaction-service) or EXPECTED_TRANSACTIONS_PATH is stale",
            )
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
        val queryNames = TransactionsApi::class.java.methods
            .single { it.name == "listTransactions" }
            .parameters
            .mapNotNull { it.getAnnotation(QueryParam::class.java)?.value }
        assertThat(queryNames).contains("accountId", "limit")
    }

    private companion object {
        const val CONSUMER = "openbank-party-service"
        const val PROVIDER = "openbank-transaction-service"
        const val OK = 200
        const val UNAUTHORIZED = 401

        const val ACCOUNT_ID = "0a3a1a2b-1111-4222-8333-444455556666"
        const val PAGE_SIZE = 200

        /** LITERAL, retyped from transaction-service's `TransactionResource`. Never derive this. */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        /**
         * The route lives on the GENERATED interface [TransactionServiceRestClient] extends (ADR-0319);
         * a `@Path` restated on the consumer sub-interface would win, so read that first.
         */
        fun clientDerivedTransactionsPath(): String {
            val base = (
                TransactionServiceRestClient::class.java.getAnnotation(Path::class.java)
                    ?: TransactionsApi::class.java.getAnnotation(Path::class.java)
                )?.value.orEmpty()
            val method = TransactionsApi::class.java.methods
                .single { it.name == "listTransactions" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + method
        }
    }
}
