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
import com.openbank.agent.infrastructure.client.BalanceServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for hop 2 of the agent's MCP read surface: `GET
 * /api/v1/balances/{accountId}`, the `get_account_balance` tool. Second of the 15 downstream
 * clients pinned after [AgentAccountLookupPactConsumerTest]; see that class for why the value
 * here is the **route being replayed against the real provider**, not the body shape — the read
 * adapter hands the raw JsonNode to the tool caller, so what silently breaks is a wrong path,
 * invisible to every mocked unit test and to the consumer pact on its own (#2269).
 *
 * Mirrors mcp-service's `BalanceReadPactConsumerTest` (ADR-0195 step 2 pinned the same endpoint
 * for the sibling read surface) but is a SEPARATE pact: the agent's client interface lives in
 * agent-service, so its `@Path` annotations are what this test reflects and pins — mcp's pact
 * cannot catch an edit here.
 *
 * No seeded data needed: the listing answers **200** with an empty `balances` array for an
 * account it holds nothing for — a 404 would prove nothing, since Quarkus answers 404 for an
 * absent route too. `minArrayLike(min = 0)` records the element shape without requiring a row;
 * `currency` stays a stringType (never pinned to CZK) because an account legitimately holds
 * CZK, EUR and USD rows here (#2425).
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
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

        fun clientDerivedBalancesPath(): String {
            val base = BalanceServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceServiceClient::class.java.methods
                .single { it.name == "getBalance" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID)
        }
    }
}
