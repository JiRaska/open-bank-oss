// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.read.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for `GET /api/v1/transactions?accountId=&limit=` — the read behind the
 * MCP `list_transactions` tool (ADR-0195 step 2). The generated pact is committed to
 * `pacts/openbank-mcp-service-openbank-transaction-service.json` (git-pact, ADR-0063) and replayed
 * by `TransactionPactProviderVerificationTest` in openbank-transaction-service.
 *
 * As with the balance read, the value here is the **route and its query contract**, not the body:
 * the client returns a raw [com.fasterxml.jackson.databind.JsonNode] that
 * [com.openbank.mcp.infrastructure.read.RealAccountReadPort] passes through untouched. What can
 * silently break is the collection route — transaction-service exposes the account listing on the
 * *base* path with `accountId` as a query parameter, while a sibling `GET /search` takes an
 * overlapping parameter set, so "which of the two did the client wire?" is a real question that
 * only a provider replay answers. `accountId` is a required `@QueryParam` on the provider (a
 * non-null `UUID`), so omitting it is a 400 — pinning it here records that.
 *
 * The provider state SEEDS one transaction for a dedicated account (#13225). The pact used to replay
 * against an empty page with `minArrayLike(min = 0)`, so its element shape was never compared with
 * anything - and it named the field `currency`, which the provider has never sent (`currencyCode`).
 * With `min = 1` and a seeded row, a renamed element field is red at the provider replay. The 200 is
 * still what proves the route exists; a 404 would not, since Quarkus answers 404 for an absent route.
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** — deriving both sides would be vacuous (CLAUDE.md "Contract tests", measured on #2290).
 * For this client the "path" is the interface-level `@Path` alone: `listTransactions` carries no
 * method-level `@Path`, which is itself part of what this pact pins.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-mcp-service:test --tests
 * "*TransactionListPactConsumerTest*"`) and commit the updated pact JSON in the same PR.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class TransactionListPactConsumerTest {

    private val mapper = ObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun transactionsForAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("transaction-service holds a transaction for the pact account")
        .uponReceiving("GET the transaction page the MCP list_transactions tool returns verbatim")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .query("accountId=$PACT_ACCOUNT_ID&limit=$PACT_LIMIT")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // min = 1: the provider state seeds a row, so the element shape is actually compared.
                o.minArrayLike("data", 1, 1) { t ->
                    t.uuid("id", UUID.fromString("9f8e7d6c-5b4a-4390-8123-456789abcdef"))
                    // The provider's field name (TransactionResponse.currencyCode), never `currency`.
                    t.stringType("currencyCode", "CZK")
                }
                o.`object`("pagination") { p ->
                    p.numberType("limit", PACT_LIMIT)
                    p.booleanType("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "transactionsForAccountPact")
    fun `the transaction page is a JsonNode the tool passes through unchanged`(mockServer: MockServer) {
        assertThat(clientDerivedTransactionsPath())
            .describedAs("TransactionServiceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("accountId", PACT_ACCOUNT_ID)
            .queryParam("limit", PACT_LIMIT)
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedTransactionsPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val node = mapper.readTree(raw)
        assertThat(node.path("data").isArray).isTrue()
        assertThat(node.path("data").first().has("currencyCode")).isTrue()
        assertThat(node.path("pagination").has("limit")).isTrue()
    }

    private companion object {
        const val CONSUMER = "openbank-mcp-service"
        const val PROVIDER = "openbank-transaction-service"

        /** transaction-service's `PactTransactionSeed.SEEDED_ACCOUNT_ID`: the state books one row for it. */
        const val PACT_ACCOUNT_ID = "e2e2e2e2-f3f3-4b4b-8c8c-d0d0d0d0d0d0"

        /** The client's own `@DefaultValue("20")`, sent explicitly so the query contract is pinned. */
        const val PACT_LIMIT = 20

        /**
         * LITERAL, retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")`, listing on the base path). Never derive from the client.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        fun clientDerivedTransactionsPath(): String =
            TransactionServiceClient::class.java.getAnnotation(Path::class.java).value
    }
}
