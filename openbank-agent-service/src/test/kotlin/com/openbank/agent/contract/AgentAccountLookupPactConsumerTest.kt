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
import com.openbank.agent.infrastructure.client.AccountServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the first hop of the agent's MCP read surface: `GET
 * /api/v1/accounts/{id}`, the `get_account` tool the chat/agent loop answers operator questions
 * with. Agent-service carries 15 downstream REST clients (`ServiceClients.kt`) and until now not
 * one of them had a pinned contract — every `@Path` drift, field rename, or dropped key surfaced
 * only as a null somewhere inside a JsonNode the LLM then hallucinated around. This pact pins the
 * shape; `AccountPactFolderProviderVerificationTest` replays it against the real Quarkus app.
 *
 * The generated pact is committed to `pacts/openbank-agent-service-openbank-account-service.json`
 * (git-pact, ADR-0063); `.github/workflows/pact-drift-check.yml` fails the build if the JSON and
 * this test diverge — regenerate by re-running this test and committing both.
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests"). Deriving both sides is vacuous — the Pact mock answers
 * whatever the client asks, so the pair would move together and stay green against a route that
 * does not exist (#2269 shipped exactly that against a never-existing ledger path). Here
 * [assertClientPathMatchesContract] pins `AccountServiceClient`'s annotations to the literal, so
 * a `@Path` edit on the client reddens THIS test, and the provider replay independently
 * adjudicates whether account-service serves it.
 *
 * The response is asserted as a [JsonNode] — that IS the client DTO here (the MCP read tool
 * serializes the whole document downstream, so field ABSENCE is load-bearing, not just shape):
 * every pinned key must exist and be textual.
 *
 * NO 401 interaction is recorded, deliberately: the provider replay boots with a TestAuthMechanism
 * that authenticates every request, so a recorded 401 can never pass replay (same reasoning as the
 * account-side pact, #8552). The negative case is the one shape replay CAN serve — a well-formed
 * id nobody holds is a DIFFERENT PATH, so account-service answers 404 under the same mechanism.
 * Enumeration resistance is a real contract: "no such account" must not arrive as an empty body.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AgentAccountLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountOwnedByKnownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account the agent's get_account tool reads for an operator")
        .path(EXPECTED_ACCOUNT_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: the tool forwards whatever account-service sends, it does not care
                // about the values. What it cannot survive is a renamed or absent key — a missing
                // `status` would read as "account ok" to the model, a missing `partyId` as an
                // account without an owner. Field absence is the failure mode, so every key the
                // response documents is pinned present.
                o.stringType("id", PACT_ACCOUNT_ID)
                o.stringType("accountNumber", "CZ6508000000192000145399")
                o.stringType("accountType", "CURRENT")
                o.stringType("partyId", PACT_OWNER_PARTY_ID)
                o.stringType("productId", "99999999-8888-4777-8666-555555555555")
                o.stringType("currencyCode", "CZK")
                o.stringType("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountOwnedByKnownPartyPact")
    fun `the account response carries every key the read tool serializes`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedAccountPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val account = mapper.readTree(raw)
        PINNED_KEYS.forEach { key ->
            assertThat(account.has(key))
                .describedAs("account-service stopped sending the '%s' key the agent's read tool serializes", key)
                .isTrue()
            assertThat(account.get(key).isTextual)
                .describedAs("the '%s' key must stay a string — a retyped field breaks the tool's contract", key)
                .isTrue()
        }
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the id")
        .uponReceiving("GET an account id the bank does not hold")
        .path(UNKNOWN_ACCOUNT_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unknownAccountPact")
    fun `an account id the bank does not hold is a 404, not an empty account`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(UNKNOWN_ACCOUNT_PATH)
            .then()
            .statusCode(404)
    }

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the client
     * would really call, recomputed from [AccountServiceClient]'s own annotations, must equal the
     * literal this pact promises account-service. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedAccountPath())
            .describedAs(
                "AccountServiceClient's @Path no longer produces the path this pact pins — either fix " +
                    "the client or update EXPECTED_ACCOUNT_PATH *and* re-verify against account-service",
            )
            .isEqualTo(EXPECTED_ACCOUNT_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-agent-service"
        const val PROVIDER = "openbank-account-service"

        /** Seeded by account-service's `an account owned by a known party exists` state. */
        const val PACT_ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PACT_OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /**
         * LITERAL, deliberately retyped from account-service's `AccountResource`
         * (`@Path("/api/v1/accounts")` + `@GET @Path("/{accountId}")`). Never derive this from the
         * client — see the class KDoc.
         */
        const val EXPECTED_ACCOUNT_PATH = "/api/v1/accounts/$PACT_ACCOUNT_ID"

        /** Seeded by account-service's `no account exists for the id` state (deliberately empty). */
        const val UNKNOWN_ACCOUNT_ID = "00000000-0000-4000-8000-000000000099"
        const val UNKNOWN_ACCOUNT_PATH = "/api/v1/accounts/$UNKNOWN_ACCOUNT_ID"

        /** Every key the 200 pact pins present — asserted existent AND textual on the JsonNode. */
        val PINNED_KEYS = listOf(
            "id",
            "accountNumber",
            "accountType",
            "partyId",
            "productId",
            "currencyCode",
            "status",
        )

        fun clientDerivedAccountPath(): String {
            val base = AccountServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = AccountServiceClient::class.java.methods
                .single { it.name == "getAccount" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{id}", PACT_ACCOUNT_ID)
        }
    }
}
