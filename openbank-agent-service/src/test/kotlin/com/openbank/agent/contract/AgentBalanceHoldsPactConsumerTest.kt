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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.infrastructure.client.BalanceServiceClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the assistant's `get_balance_holds` read tool against
 * balance-service (`BalanceServiceClient.getHolds`, dispatched by [RestDownstreamReadAdapter]).
 *
 * This tool was REGISTERED AND DOCUMENTED while the endpoint did not exist: the dispatch
 * pointed at `GET /api/v1/balances/{accountId}/holds`, which only POST (placeHold) served, so
 * every assistant read of holds was refused. The pact ships together with the endpoint
 * (feat(balance) in the same PR) so the two can never drift apart again — this is the
 * contract test the API-change rule requires, written from the consumer side.
 *
 * Provider verification lives in balance-service's BalancePactFolderProviderVerificationTest:
 * the 200 leg reuses its `a CZK hold exists for the holds account` seed (constants below must
 * match), the empty leg reuses `balance-service is reachable and holds no balances for the
 * pact account` — a no-op state, since the requested account is one no state ever seeds.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class AgentBalanceHoldsPactConsumerTest {

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

    private companion object {
        val mapper = jacksonObjectMapper()

        /** Must match BalancePactFolderProviderVerificationTest's RELEASE_ACCOUNT_ID / RELEASE_HOLD_ID. */
        const val HOLDS_ACCOUNT_ID = "c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3"
        const val HOLD_ID = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2"

        /** Deliberately never seeded on the provider side — the empty leg leans on that. */
        const val EMPTY_ACCOUNT_ID = "e4e4e4e4-e4e4-4e4e-8e4e-e4e4e4e4e4e4"
    }
}
