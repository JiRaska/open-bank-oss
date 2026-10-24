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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.infrastructure.client.BalanceServiceClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for hops 2 and 4 of the agent's MCP read surface against
 * balance-service, all merged into the ONE pact file
 * `pacts/openbank-agent-service-openbank-balance-service.json` (PactConsumerTestExt merges all
 * `@Pact` methods of a class per provider — a second class would overwrite the file, measured
 * during this PR):
 *
 * 1. `GET /api/v1/balances/{accountId}` — the `get_account_balance` tool. Second of the 15
 *    downstream clients pinned after [AgentAccountLookupPactConsumerTest]; see that class for why
 *    the value here is the **route being replayed against the real provider**, not the body shape
 *    — the read adapter hands the raw JsonNode to the tool caller, so what silently breaks is a
 *    wrong path, invisible to every mocked unit test and to the consumer pact on its own (#2269).
 *    Mirrors mcp-service's `BalanceReadPactConsumerTest` (ADR-0195 step 2 pinned the same endpoint
 *    for the sibling read surface) but is a SEPARATE pact: the agent's client interface lives in
 *    agent-service, so its `@Path` annotations are what this test reflects and pins — mcp's pact
 *    cannot catch an edit here. No seeded data needed: the listing answers **200** with an empty
 *    `balances` array for an account it holds nothing for — a 404 would prove nothing, since
 *    Quarkus answers 404 for an absent route too. `minArrayLike(min = 0)` records the element
 *    shape without requiring a row; `currency` stays a stringType (never pinned to CZK) because an
 *    account legitimately holds CZK, EUR and USD rows here (#2425).
 * 2. `GET /api/v1/balances/{accountId}/holds` — the `get_balance_holds` tool. Unlike the balance
 *    listing, this pact demands `minArrayLike("holds", 1)`: the provider state seeds one
 *    genuinely ACTIVE hold, because an empty array would match any envelope and prove nothing
 *    about the element shape. Pinned keys are the load-bearing ones (a renamed `reason` or
 *    `referenceId` changes what the operator sees; a retyped `amount` breaks consumers);
 *    `expiresAt`/`releasedAt` stay unpinned because they are legitimately null on an active hold.
 *
 * Additional main-branch contracts cover a second active hold, an empty holds list, and a 401
 * without identity. They use the production read adapter, so tool dispatch and pass-through are
 * exercised rather than merely mocking the provider route.
 *
 * **The expected paths are LITERALS; only the outgoing requests are reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290).
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-agent-service:test
 * --tests "*AgentBalanceReadPactConsumerTest*"`) and commit the updated pact JSON in the same PR;
 * `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class AgentBalanceReadPactConsumerTest {

    private val mapper = ObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun balancesForAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("balance-service is reachable and holds no balances for the pact account")
        .uponReceiving("GET the balances the agent's get_account_balance tool returns verbatim")
        .path(EXPECTED_BALANCES_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // min = 0: the provider replays against a fresh Testcontainer DB with no balances.
                o.minArrayLike("balances", 0, 1) { b ->
                    b.stringType("currency", "CZK")
                    b.decimalType("available", 1_000.00)
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "balancesForAccountPact")
    fun `the balances response is a JsonNode the tool passes through unchanged`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedBalancesPath())
            .then()
            .statusCode(200)
            .extract().asString()

        // The adapter hands this straight to the tool caller, so "parses as JSON and carries the
        // envelope key" is the whole of agent's dependency on the body.
        val node = mapper.readTree(raw)
        assertThat(node.has("balances")).isTrue()
        assertThat(node.path("balances").isArray).isTrue()
    }

    // --- Second interaction of the SAME pact file: the holds listing. ---
    //
    // Both @Pact methods in this class merge into the one
    // `pacts/openbank-agent-service-openbank-balance-service.json` (PactConsumerTestExt merges all
    // @Pact methods of a class per provider). A second CLASS with its own @PactTestFor would
    // OVERWRITE that file with only its own interaction — measured during this PR: the holds
    // contract briefly evicted the balance-read one from the pact file. If you add a third
    // balance interaction, add a third @Pact method HERE, never a new class.
    //
    // This interaction exists because of what its provider replay caught while it was being
    // written: the route DID NOT EXIST. BalanceResource served POST (placeHold) and DELETE
    // (releaseHold) at the holds paths but no GET listing, so every live `get_balance_holds` call
    // answered **405** — the exact #2269 failure class (a call to a path the provider never
    // served, invisible to mocked unit tests). The GET endpoint was added to balance-service in
    // this PR; this pact now pins it so the route can never silently disappear again.

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeHoldsForAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an active CZK hold exists for the agent holds account")
        .uponReceiving("GET the holds the agent's get_balance_holds tool returns verbatim")
        .path(EXPECTED_HOLDS_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // min = 1, deliberately: the provider state seeds one ACTIVE hold, so the replay
                // must prove both the route and the element shape, not just the envelope.
                o.minArrayLike("holds", 1, 1) { h ->
                    h.stringType("id", PACT_HOLD_ID)
                    h.stringType("accountId", PACT_ACCOUNT_ID_FOR_HOLDS)
                    h.decimalType("amount", 100.00)
                    h.stringType("currency", "CZK")
                    h.stringType("reason", "payment-cover")
                    h.stringType("referenceId", "pact-agent-hold-0001")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeHoldsForAccountPact")
    fun `the holds listing carries the element shape the tool serializes`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedHoldsPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val node = mapper.readTree(raw)
        assertThat(node.path("holds").isArray).isTrue()
        val hold = node.path("holds").path(0)
        listOf("id", "accountId", "currency", "reason", "referenceId").forEach { key ->
            assertThat(hold.path(key).isTextual)
                .describedAs("the '%s' key must stay a string — a retyped field breaks the tool's contract", key)
                .isTrue()
        }
    }

    // Main contributed the empty, authenticated, and missing-identity interactions. Keep all
    // five interactions in this class; separate PactConsumerTestExt classes overwrite one file.
    @Pact(consumer = "openbank-agent-service", provider = "openbank-balance-service")
    fun listHoldsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK hold exists for the holds account")
        .uponReceiving("GET the active holds behind the get_balance_holds tool")
        .path("/api/v1/balances/$HOLDS_ACCOUNT_ID/holds")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("holds") { holds ->
                    holds.`object` { h ->
                        h.uuid("id", UUID.fromString(HOLD_ID))
                        h.uuid("accountId", UUID.fromString(HOLDS_ACCOUNT_ID))
                        h.decimalType("amount")
                        h.stringValue("currency", "CZK")
                        h.stringValue("reason", "payment-cover")
                        h.stringValue("referenceId", "pact-tx-00000001")
                    }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-balance-service")
    fun listHoldsEmptyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("balance-service is reachable and holds no balances for the pact account")
        .uponReceiving("GET the holds of an account that has none, via the get_balance_holds tool")
        .path("/api/v1/balances/$EMPTY_ACCOUNT_ID/holds")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("holds") { }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-balance-service")
    fun listHoldsMissingIdentityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("GET active holds without an authenticated identity")
        .path("/api/v1/balances/$HOLDS_ACCOUNT_ID/holds")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listHoldsMissingIdentityPact")
    fun `get_balance_holds propagates 401 without identity`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read("get_balance_holds", args("""{"accountId": "$HOLDS_ACCOUNT_ID"}"""))
        }.isInstanceOfSatisfying(WebApplicationException::class.java) {
            assertThat(it.response.status).isEqualTo(401)
        }
    }

    @Test
    @PactTestFor(pactMethod = "listHoldsPact")
    fun `get_balance_holds returns the seeded active hold verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "get_balance_holds",
            args("""{"accountId": "$HOLDS_ACCOUNT_ID"}"""),
        )
        assertThat(result.get("holds")).hasSize(1)
        assertThat(result.get("holds")[0].get("id").asText()).isEqualTo(HOLD_ID)
        assertThat(result.get("holds")[0].get("referenceId").asText()).isEqualTo("pact-tx-00000001")
        // Untouched fields survive the pass-through to the model.
        assertThat(result.get("holds")[0].get("currency").asText()).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "listHoldsEmptyPact")
    fun `an account without holds reads as an empty array, not an error`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "get_balance_holds",
            args("""{"accountId": "$EMPTY_ACCOUNT_ID"}"""),
        )
        assertThat(result.get("holds")).isEmpty()
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its balance client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { balanceClient = balanceClientFor(mockServer) }

    private fun balanceClientFor(mockServer: MockServer): BalanceServiceClient = object : BalanceServiceClient {
        override fun getBalance(accountId: String): JsonNode = throw UnsupportedOperationException("not under pact")

        override fun getHolds(accountId: String): JsonNode = get(mockServer, "/api/v1/balances/$accountId/holds")

        private fun get(mockServer: MockServer, path: String): JsonNode {
            val response = given().baseUri(mockServer.getUrl()).contentType("application/json").get(path)
            if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
            return mapper.readTree(response.asString())
        }
    }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedBalancesPath())
            .describedAs(
                "BalanceServiceClient's @Path no longer produces the path this pact pins — either fix " +
                    "the client or update EXPECTED_BALANCES_PATH *and* re-verify against balance-service",
            )
            .isEqualTo(EXPECTED_BALANCES_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-agent-service"
        const val PROVIDER = "openbank-balance-service"

        /** Any well-formed UUID: the provider state deliberately holds no balances for it. */
        const val PACT_ACCOUNT_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b9b9b9b9b9b9"

        /**
         * LITERAL, deliberately retyped from balance-service's `BalanceResource`
         * (`@Path("/api/v1/balances")` + `@GET @Path("/{accountId}")`). Never derive from the client.
         */
        const val EXPECTED_BALANCES_PATH = "/api/v1/balances/$PACT_ACCOUNT_ID"

        /** Seeded by balance-service's `an active CZK hold exists for the agent holds account` state. */
        const val PACT_ACCOUNT_ID_FOR_HOLDS = "d2d2d2d2-d2d2-42d2-82d2-d2d2d2d2d2d2"
        const val PACT_HOLD_ID = "e2e2e2e2-e2e2-42e2-82e2-e2e2e2e2e2e2"

        /** Separate provider fixtures retained from main for a second nonempty and empty holds case. */
        const val HOLDS_ACCOUNT_ID = "c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3"
        const val HOLD_ID = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2"
        const val EMPTY_ACCOUNT_ID = "e4e4e4e4-e4e4-4e4e-8e4e-e4e4e4e4e4e4"

        /**
         * LITERAL, deliberately retyped from balance-service's `BalanceResource`
         * (`@Path("/api/v1/balances")` + `@GET @Path("/{accountId}/holds")`). Never derive from the
         * client — and note this GET was MISSING on the provider until this PR's endpoint addition.
         */
        const val EXPECTED_HOLDS_PATH = "/api/v1/balances/$PACT_ACCOUNT_ID_FOR_HOLDS/holds"

        fun clientDerivedBalancesPath(): String {
            val base = BalanceServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceServiceClient::class.java.methods
                .single { it.name == "getBalance" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID)
        }

        fun clientDerivedHoldsPath(): String {
            val base = BalanceServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceServiceClient::class.java.methods
                .single { it.name == "getHolds" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID_FOR_HOLDS)
        }
    }
}
