// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

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
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the assistant's `get_transaction` read tool against
 * transaction-service (`TransactionServiceClient.getTransaction`, wired into the MCP read
 * dispatch in [RestDownstreamReadAdapter]). `list_transactions` is deliberately NOT pinned
 * here — mcp-service's TransactionListPactConsumerTest already covers
 * `GET /api/v1/transactions` (ADR-0195) and this service's adapter adds nothing over it.
 *
 * The model receives the downstream body VERBATIM, so the contract pins the
 * tool-name-to-endpoint mapping for the by-id read and that a missing transaction FAILS
 * CLOSED as an exception rather than being synthesised into a plausible-looking empty
 * transaction.
 *
 * Provider verification lives in transaction-service's
 * TransactionPactFolderProviderVerificationTest. The 200 leg reuses the posted-transaction
 * seed `a posted transaction exists for the pact account` (constants below must match its
 * companion — shared with party-service's portability pact #13060, so the two PRs converge
 * on identical provider state); the 404 leg reuses `no transactions exist for the requested
 * account` with a transaction id no state ever seeds.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class AgentTransactionReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-transaction-service")
    fun getTransactionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a posted transaction exists for the pact account")
        .uponReceiving("GET the transaction behind the get_transaction tool")
        .path("/api/v1/transactions/$TRANSACTION_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // The adapter passes the body to the model verbatim and branches on nothing, so the
            // pact pins only the identifying fields — provider responses carrying more
            // TransactionResponse fields are deliberately not matched against this consumer.
            newJsonBody { o ->
                o.uuid("id", java.util.UUID.fromString(TRANSACTION_ID))
                o.stringValue("referenceNumber", REFERENCE_NUMBER)
                o.stringValue("type", "CREDIT")
                o.stringValue("status", "COMPLETED")
                o.stringValue("currencyCode", "CZK")
                o.decimalType("amount")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-transaction-service")
    fun getTransactionMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no transactions exist for the requested account")
        .uponReceiving("GET a transaction id transaction-service does not hold, via the get_transaction tool")
        .path("/api/v1/transactions/$MISSING_TRANSACTION_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getTransactionPact")
    fun `get_transaction returns the downstream transaction verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "get_transaction",
            args("""{"transactionId": "$TRANSACTION_ID"}"""),
        )
        assertThat(result.get("id").asText()).isEqualTo(TRANSACTION_ID)
        assertThat(result.get("referenceNumber").asText()).isEqualTo(REFERENCE_NUMBER)
        // The whole point of the verbatim contract: untouched fields survive the pass-through.
        assertThat(result.get("status").asText()).isEqualTo("COMPLETED")
    }

    @Test
    @PactTestFor(pactMethod = "getTransactionMissingPact")
    fun `a missing transaction fails closed instead of returning an empty transaction`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read("get_transaction", args("""{"transactionId": "$MISSING_TRANSACTION_ID"}"""))
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its transaction client bound to the pact
     * mock — the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { transactionClient = transactionClientFor(mockServer) }

    private fun transactionClientFor(mockServer: MockServer): TransactionServiceClient =
        object : TransactionServiceClient {
            override fun getTransaction(id: String): JsonNode = get(mockServer, "/api/v1/transactions/$id")

            override fun listTransactions(accountId: String, limit: Int, cursor: String?): JsonNode =
                throw UnsupportedOperationException("covered by mcp-service's pact, not this one")

            private fun get(mockServer: MockServer, path: String): JsonNode {
                val response = given().baseUri(mockServer.getUrl()).contentType("application/json").get(path)
                if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
                return mapper.readTree(response.asString())
            }
        }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private companion object {
        val mapper = jacksonObjectMapper()

        /** Must match TransactionPactFolderProviderVerificationTest's seed constants (#13060). */
        const val TRANSACTION_ID = "88888888-9999-4aaa-bbbb-cccccccccccc"
        const val REFERENCE_NUMBER = "TXN-PARTY-PACT-001"

        /** Deliberately never seeded on the provider side — the 404 leg leans on that. */
        const val MISSING_TRANSACTION_ID = "99999999-0000-4bbb-8ccc-dddddddddddd"
    }
}
