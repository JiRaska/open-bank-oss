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
import java.util.UUID

/**
 * Consumer-driven contract for the assistant's account read tools against account-service
 * (`AccountServiceClient`, wired into the MCP read dispatch in [RestDownstreamReadAdapter]).
 * The model receives the downstream body VERBATIM — the adapter does not transform it — so
 * the contract pins two things: the tool-name-to-endpoint mapping, and that a downstream
 * error propagates as an exception instead of being synthesised into a plausible-looking
 * empty account (fail closed, ADR-0063).
 *
 * Provider verification lives in account-service's
 * AccountPactFolderProviderVerificationTest: the 200 legs reuse its seeded
 * `an account owned by a known party exists` state (ACCOUNT_ID / IBAN below must match its
 * constants), and the 404 leg reuses `no account exists for the IBAN` with the same
 * never-seeded IBAN the standing-order and vop pacts use.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AgentAccountReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-account-service")
    fun getAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account behind the get_account tool")
        .path("/api/v1/accounts/$ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // The adapter passes the body to the model verbatim and branches on nothing, so the
            // pact matches only the fields that identify the resource — the provider's real
            // response carries fifteen AccountResponse fields the pact deliberately ignores.
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(OWNER_PARTY_ID))
                o.stringValue("accountNumber", SEEDED_IBAN)
                o.stringValue("currencyCode", "CZK")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-account-service")
    fun getAccountByIbanPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account behind the get_account_by_iban tool")
        .path("/api/v1/accounts/iban/$SEEDED_IBAN")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(OWNER_PARTY_ID))
                o.stringValue("accountNumber", SEEDED_IBAN)
                o.stringValue("currencyCode", "CZK")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-account-service")
    fun getAccountByIbanMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the IBAN")
        .uponReceiving("GET an IBAN account-service does not hold, via the get_account_by_iban tool")
        .path("/api/v1/accounts/iban/$MISSING_IBAN")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getAccountPact")
    fun `get_account returns the downstream account verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_account", args("""{"accountId": "$ACCOUNT_ID"}"""))
        assertThat(result.get("id").asText()).isEqualTo(ACCOUNT_ID)
        assertThat(result.get("partyId").asText()).isEqualTo(OWNER_PARTY_ID)
        // The whole point of the verbatim contract: untouched fields survive the pass-through.
        assertThat(result.get("accountNumber").asText()).isEqualTo(SEEDED_IBAN)
    }

    @Test
    @PactTestFor(pactMethod = "getAccountByIbanPact")
    fun `get_account_by_iban resolves the seeded IBAN`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_account_by_iban", args("""{"iban": "$SEEDED_IBAN"}"""))
        assertThat(result.get("id").asText()).isEqualTo(ACCOUNT_ID)
    }

    @Test
    @PactTestFor(pactMethod = "getAccountByIbanMissingPact")
    fun `a missing IBAN fails closed instead of returning an empty account`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read("get_account_by_iban", args("""{"iban": "$MISSING_IBAN"}"""))
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its account client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { accountClient = accountClientFor(mockServer) }

    private fun accountClientFor(mockServer: MockServer): AccountServiceClient = object : AccountServiceClient {
        override fun getAccount(id: String): JsonNode = get(mockServer, "/api/v1/accounts/$id")

        override fun getBalance(id: String): JsonNode = throw UnsupportedOperationException("not under pact")

        override fun getAccountByIban(iban: String): JsonNode = get(mockServer, "/api/v1/accounts/iban/$iban")

        override fun getInfo(): JsonNode = throw UnsupportedOperationException("not under pact")

        private fun get(mockServer: MockServer, path: String): JsonNode {
            val response = given().baseUri(mockServer.getUrl()).contentType("application/json").get(path)
            if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
            return mapper.readTree(response.asString())
        }
    }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private companion object {
        val mapper = jacksonObjectMapper()

        /** Must match AccountPactFolderProviderVerificationTest's ACCOUNT_ID / OWNER_PARTY_ID. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /** The IBAN that provider state seeds; MISSING_IBAN is the one no state ever seeds. */
        const val SEEDED_IBAN = "CZ6508000000192000145399"
        const val MISSING_IBAN = "CZ6508000000192000145981"
    }
}
