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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the assistant's ledger read tools against ledger-service
 * (`LedgerServiceClient`, dispatched by [RestDownstreamReadAdapter] as `list_ledger_journals` /
 * `get_trial_balance`). The model receives the downstream body VERBATIM, so the contract pins
 * the tool-name-to-endpoint mapping and the page/trial-balance envelope shapes. These are
 * regulatory reads: a trial balance the consumer cannot parse is a reconciliation the agent
 * silently loses.
 *
 * Provider verification replays against ledger's LedgerPactProviderVerificationTest, reusing the
 * EXISTING `ledger has journal entries for the reporting date` state (a no-op: a fresh
 * Testcontainer DB may hold zero entries) — so every array is pinned min=0, the same discipline
 * as balance-service's own trial-balance pact (issue #2425). No new provider states: this pact
 * documents the agent's dispatch, it does not need new fixtures. The class-level @PactFilter
 * there drops only the negative-auth state, so these replay normally.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class AgentLedgerReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-ledger-service")
    fun listJournalsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has journal entries for the reporting date")
        .uponReceiving("GET the journal page behind the list_ledger_journals tool")
        .path("/api/v1/journals")
        .query("limit=20")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // listJournals returns a CursorPage envelope. The journal population is GLOBAL —
                // the provider JVM carries every ledger pact's seeds — so the contract pins each
                // row's shape (minArrayLike), never a count, order, or a specific entry.
                o.minArrayLike("data", 0, 1) { j ->
                    j.uuid("id")
                    j.stringType("status")
                    j.stringType("entryDate")
                    j.booleanType("synthetic")
                    j.minArrayLike("lines", 1, 1) { line ->
                        line.stringType("side")
                        line.decimalType("amount")
                        line.stringType("currencyCode")
                    }
                }
                o.`object`("pagination") { p ->
                    // PageInfo.limit echoes the limit= query parameter this interaction pins.
                    p.numberValue("limit", 20)
                    p.booleanType("hasNextPage")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-ledger-service")
    fun trialBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has journal entries for the reporting date")
        .uponReceiving("GET the trial balance behind the get_trial_balance tool")
        .path("/api/v1/journals/trial-balance")
        .query("asOf=2024-01-31")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue, NOT stringType (issue #2425, same rule as balance-service's pact):
                // asOf is echoed from the pinned query literal — a balance returned for a
                // DIFFERENT date than the one asked for is a reporting defect of the first order.
                o.stringValue("asOf", "2024-01-31")
                o.stringType("scope")
                o.decimalType("totalDebit")
                o.decimalType("totalCredit")
                o.booleanType("balanced")
                // minArrayLike(0): the fresh-DB provider may legitimately answer zero lines.
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code")
                    line.stringType("currency")
                    line.decimalType("totalDebit")
                    line.decimalType("totalCredit")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-ledger-service")
    fun trialBalanceDefaultDatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has journal entries for the reporting date")
        .uponReceiving("GET the trial balance with no asOf, behind the get_trial_balance tool")
        .path("/api/v1/journals/trial-balance")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // No asOf pinned on the request: the provider echoes LocalDate.now, so there is
                // no literal to pin — stringType is the honest matcher here (the opposite of the
                // explicit-date leg, and the reason that leg exists).
                o.stringType("asOf")
                o.stringType("scope")
                o.booleanType("balanced")
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code")
                    line.decimalType("net")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listJournalsPact")
    fun `list_ledger_journals returns a cursor page of journal entries`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("list_ledger_journals", args("{}"))
        assertThat(result.get("data").isArray).isTrue()
        assertThat(result.get("pagination").get("limit").asInt()).isEqualTo(20)
        if (result.get("data").size() > 0) {
            assertThat(result.get("data")[0].get("id").isTextual).isTrue()
            assertThat(result.get("data")[0].get("lines").isArray).isTrue()
        }
    }

    @Test
    @PactTestFor(pactMethod = "trialBalancePact")
    fun `get_trial_balance echoes the requested as-of date`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_trial_balance", args("""{"asOf": "2024-01-31"}"""))
        assertThat(result.get("asOf").asText()).isEqualTo("2024-01-31")
        assertThat(result.get("balanced").asBoolean()).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "trialBalanceDefaultDatePact")
    fun `get_trial_balance without asOf answers for today`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_trial_balance", args("{}"))
        assertThat(result.get("asOf").isTextual).isTrue()
        assertThat(result.has("balanced")).isTrue()
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its ledger client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { ledgerClient = ledgerClientFor(mockServer) }

    private fun ledgerClientFor(mockServer: MockServer): LedgerServiceClient = object : LedgerServiceClient {
        override fun listJournals(limit: Int): JsonNode = get(mockServer, "/api/v1/journals?limit=$limit")

        override fun trialBalance(asOf: String?): JsonNode = get(
            mockServer,
            if (asOf == null) {
                "/api/v1/journals/trial-balance"
            } else {
                "/api/v1/journals/trial-balance?asOf=$asOf"
            },
        )

        private fun get(mockServer: MockServer, path: String): JsonNode {
            val response = given().baseUri(mockServer.getUrl()).contentType("application/json").get(path)
            if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
            return mapper.readTree(response.asString())
        }
    }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}
