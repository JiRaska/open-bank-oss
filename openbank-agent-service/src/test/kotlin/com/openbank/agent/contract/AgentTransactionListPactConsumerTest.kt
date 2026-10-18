// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for hop 3 of the agent's MCP read surface: `GET
 * /api/v1/transactions?accountId=&limit=`, the `list_transactions` tool. What can silently break
 * is the **collection route** — transaction-service exposes the account listing on the base path
 * with `accountId` as a query parameter while a sibling `GET /search` takes an overlapping
 * parameter set, so "which of the two did the client wire?" is a real question only the provider
 * replay answers. `accountId` is a required `@QueryParam` on the provider (a non-null UUID), so
 * omitting it is a 400 — pinning it here records that.
 *
 * Mirrors mcp-service's `TransactionListPactConsumerTest` but is a SEPARATE pact: the agent's
 * client interface is what this test reflects, so an edit to agent's `@Path`/`@QueryParam`
 * annotations must redden here, not in mcp's test.
 *
 * No seeded data needed: the listing answers **200** with an empty `data` array and a
 * `pagination` envelope for an account with no transactions — the 200 proves the route exists,
 * where a 404 would not (Quarkus answers 404 for an absent route too). `minArrayLike(min = 0)`
 * records the element shape without requiring a row.
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). The limit is the client's own
 * `@DefaultValue("20")`, sent explicitly so the query contract is pinned.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-agent-service:test
 * --tests "*AgentTransactionListPactConsumerTest*"`) and commit the updated pact JSON in the same
 * PR; `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 *
 * This class now carries THREE interactions of the one pact file (list 200, detail 200, detail
 * 404). PactConsumerTestExt merges all `@Pact` methods of a class into the single
 * consumer/provider pact file — a second class would OVERWRITE it with only its own interaction
 * (measured on the balance pact this PR). Add further transaction interactions as `@Pact` methods
 * here, never as a new class.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class AgentTransactionListPactConsumerTest {

    private val mapper = ObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun transactionsForAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("transaction-service is reachable and holds no transactions for the pact account")
        .uponReceiving("GET the transaction page the agent's list_transactions tool returns verbatim")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .query("accountId=$PACT_ACCOUNT_ID&limit=$PACT_LIMIT")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // min = 0: the provider replays against a fresh Testcontainer DB with no rows.
                o.minArrayLike("data", 0, 1) { t ->
                    t.stringType("id", "9f8e7d6c-5b4a-4390-8123-456789abcdef")
                    t.stringType("currency", "CZK")
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
        assertClientPathMatchesContract()

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
        assertThat(node.path("pagination").has("limit")).isTrue()
    }

    // --- Second and third interactions of the SAME pact file: the detail read. ---
    //
    // Both @Pact methods in this class merge into the one
    // `pacts/openbank-agent-service-openbank-transaction-service.json`; a second CLASS would
    // overwrite it (measured on the balance pact this PR — see AgentBalanceReadPactConsumerTest).
    //
    // `GET /api/v1/transactions/{id}` — the `get_transaction` tool. The 200 interaction is seeded
    // by transaction-service's `a completed transaction exists for the pact detail read` state
    // (COMPLETED so the terminal fields exist on the wire); the 404 is the enumeration-resistance
    // case — "no such transaction" must not arrive as an empty body the model reads as a blank
    // transaction.

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun transactionDetailPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a completed transaction exists for the pact detail read")
        .uponReceiving("GET the transaction the agent's get_transaction tool returns verbatim")
        .path(EXPECTED_DETAIL_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers on the load-bearing keys only: the tool serializes the whole
                // document downstream, so a renamed or retyped key is the failure mode. Nullable
                // fields (completedAt is set here, but failedAt/failureReason are not) stay
                // unpinned — pinning them would assert values the domain legitimately leaves null.
                o.stringType("id", PACT_TX_ID)
                o.stringType("referenceNumber", "TXN-PACT-AGENT-001")
                o.stringType("type", "DEBIT")
                o.stringType("sourceAccountId", PACT_TX_SOURCE_ACCOUNT_ID)
                o.stringType("targetAccountId", PACT_TX_TARGET_ACCOUNT_ID)
                o.decimalType("amount", 250.00)
                o.stringType("currencyCode", "CZK")
                o.stringType("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "transactionDetailPact")
    fun `the transaction detail carries the keys the tool serializes`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedDetailPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val node = mapper.readTree(raw)
        listOf("id", "referenceNumber", "type", "sourceAccountId", "targetAccountId", "currencyCode", "status")
            .forEach { key ->
                assertThat(node.path(key).isTextual)
                    .describedAs("the '%s' key must stay a string — a retyped field breaks the tool's contract", key)
                    .isTrue()
            }
        assertThat(node.path("amount").isNumber).isTrue()
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownTransactionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no transaction exists for the id")
        .uponReceiving("GET a transaction id the bank does not hold")
        .path(UNKNOWN_DETAIL_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unknownTransactionPact")
    fun `a transaction id the bank does not hold is a 404, not an empty transaction`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(UNKNOWN_DETAIL_PATH)
            .then()
            .statusCode(404)
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs(
                "TransactionServiceClient's @Path no longer produces the path this pact pins — either " +
                    "fix the client or update EXPECTED_TRANSACTIONS_PATH *and* re-verify against " +
                    "transaction-service",
            )
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-agent-service"
        const val PROVIDER = "openbank-transaction-service"

        /** Any well-formed UUID: the provider state deliberately holds no transactions for it. */
        const val PACT_ACCOUNT_ID = "e1e1e1e1-f2f2-4a4a-8b8b-c9c9c9c9c9c9"

        /** The client's own `@DefaultValue("20")`, sent explicitly so the query contract is pinned. */
        const val PACT_LIMIT = 20

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")`, listing on the base path). Never derive from the client.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        /** Seeded by transaction-service's `a completed transaction exists for the pact detail read`. */
        const val PACT_TX_ID = "f1f1f1f1-f1f1-41f1-81f1-f1f1f1f1f1f1"
        const val PACT_TX_SOURCE_ACCOUNT_ID = "f2f2f2f2-f2f2-42f2-82f2-f2f2f2f2f2f2"
        const val PACT_TX_TARGET_ACCOUNT_ID = "f3f3f3f3-f3f3-43f3-83f3-f3f3f3f3f3f3"

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")` + `@GET @Path("/{transactionId}")`). Never derive from
         * the client.
         */
        const val EXPECTED_DETAIL_PATH = "/api/v1/transactions/$PACT_TX_ID"

        /** Seeded by transaction-service's `no transaction exists for the id` (deliberately empty). */
        const val UNKNOWN_TX_ID = "00000000-0000-4000-8000-0000000000ff"
        const val UNKNOWN_DETAIL_PATH = "/api/v1/transactions/$UNKNOWN_TX_ID"

        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = TransactionServiceClient::class.java.methods
                .single { it.name == "listTransactions" }
                .getAnnotation(Path::class.java)
                .value
            return base + method
        }

        fun clientDerivedDetailPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = TransactionServiceClient::class.java.methods
                .single { it.name == "getTransaction" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{id}", PACT_TX_ID)
        }
    }
}
