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
import com.openbank.mcp.infrastructure.read.BalanceServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for `GET /api/v1/balances/{accountId}` — the balance read behind the MCP
 * `get_balance` tool (ADR-0195 step 2). The generated pact is committed to
 * `pacts/openbank-mcp-service-openbank-balance-service.json` (git-pact, ADR-0063) and replayed by
 * `BalancePactProviderVerificationTest` in openbank-balance-service.
 *
 * What this pact is FOR, and it is not the response shape: mcp's client returns a raw
 * [com.fasterxml.jackson.databind.JsonNode] which
 * [com.openbank.mcp.infrastructure.read.RealAccountReadPort] passes straight through to the tool
 * caller, so no field of the body is load-bearing on this side. The **route** is. A wrong path is
 * invisible to every unit test (the port is mocked) and invisible to a consumer pact on its own
 * (the mock server answers anything) — only the provider replay can catch it, and #2269 is what
 * happens when nobody replays: finrep shipped a call to a ledger path that had never existed. So
 * this contract exists to be replayed, and the assertion here is deliberately thin.
 *
 * The interaction needs no seeded data: `BalanceResource.getBalances` skips its owner check when no
 * `X-Customer-Party-Id` header is sent (mcp never sends one — it is a service-to-service read) and
 * answers **200** with an empty `balances` array for an account it holds nothing for. That 200 is
 * the whole point: a 404 would prove nothing, because Quarkus answers 404 for an absent route too.
 * `minArrayLike(min = 0)` therefore matches the provider's empty-DB response while still recording
 * the element shape (the same reason `LedgerTrialBalancePactConsumerTest` uses min = 0).
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** — deriving both sides would be vacuous (CLAUDE.md "Contract tests", measured on #2290).
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-mcp-service:test --tests
 * "*BalanceReadPactConsumerTest*"`) and commit the updated pact JSON in the same PR.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class BalanceReadPactConsumerTest {

    private val mapper = ObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun balancesForAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("balance-service is reachable and holds no balances for the pact account")
        .uponReceiving("GET the balances the MCP get_balance tool returns verbatim")
        .path(EXPECTED_BALANCES_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // min = 0: the provider replays against a fresh Testcontainer DB with no balances.
                // The element template records the shape without requiring a row to exist.
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
        assertThat(clientDerivedBalancesPath())
            .describedAs("BalanceServiceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_BALANCES_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedBalancesPath())
            .then()
            .statusCode(200)
            .extract().asString()

        // The port hands this straight to the caller, so "parses as JSON and carries the envelope
        // key" is the whole of mcp's dependency on the body.
        val node = mapper.readTree(raw)
        assertThat(node.has("balances")).isTrue()
        assertThat(node.path("balances").isArray).isTrue()
    }

    private companion object {
        const val CONSUMER = "openbank-mcp-service"
        const val PROVIDER = "openbank-balance-service"

        /** Any well-formed UUID: the provider state deliberately holds no balances for it. */
        const val PACT_ACCOUNT_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b9b9b9b9b9b9"

        /**
         * LITERAL, retyped from balance-service's `BalanceResource` (`@Path("/api/v1/balances")` +
         * `@GET @Path("/{accountId}")`). Never derive this from the client.
         */
        const val EXPECTED_BALANCES_PATH = "/api/v1/balances/$PACT_ACCOUNT_ID"

        fun clientDerivedBalancesPath(): String {
            val base = BalanceServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceServiceClient::class.java.methods
                .single { it.name == "getBalances" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID)
        }
    }
}
