// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslJsonArray
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
 * Consumer-driven contract for the assistant's sanctions read tools against sanctions-service
 * (`SanctionsServiceClient`, dispatched by [RestDownstreamReadAdapter] as `sanctions_list_checks` /
 * `sanctions_get_check` / `sanctions_list_pending`). The model receives the downstream body
 * VERBATIM, so the contract pins the tool-name-to-endpoint mapping and that an unknown check id
 * FAILS CLOSED as an exception rather than a synthesised "clear" verdict — a made-up sanctions
 * answer is a compliance lie.
 *
 * Provider verification lives in sanctions' SanctionsPactProviderVerificationTest, seeded by
 * AgentSanctionsPactSeed: one POTENTIAL_HIT check for a probe name no list carries, so the list
 * legs see at least one row and the get leg pins a real 200. The list population is GLOBAL (the
 * provider JVM also replays screen-based consumers, each persisting its own check), so list legs
 * pin per-row SHAPE via arrayEachLike — never a count, order, or a value only one row carries.
 * The /pending leg MAY pin status=POTENTIAL_HIT on every element: listPending() filters by
 * exactly that status, so the filter itself is the guarantee. The class-level @PactFilter there
 * drops only the negative-auth state, so these replay normally.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sanctions-service", pactVersion = PactSpecVersion.V3)
class AgentSanctionsReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sanctions-service")
    fun listChecksPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a sanctions check exists for the pact agent probe")
        .uponReceiving("GET the check list behind the sanctions_list_checks tool")
        .path("/api/v1/sanctions")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // listChecks serialises a BARE array. Every element is a SanctionsCheck DTO, so the
            // type matchers hold for every row any pact's screen interactions leave behind.
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                stringType("entityType")
                stringType("name")
                stringType("status")
                decimalType("overallScore")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sanctions-service")
    fun getCheckPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a sanctions check exists for the pact agent probe")
        .uponReceiving("GET the check behind the sanctions_get_check tool")
        .path("/api/v1/sanctions/5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Pinned to AgentSanctionsPactSeed's exact row: getById returns that one check, so the
            // identity fields are pinned by value, not just by type. An empty matches list is the
            // honest fixture shape — the row is a seed, not a screening verdict.
            newJsonBody { o ->
                o.stringValue("id", "5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d")
                o.stringValue("idempotencyKey", "pact-agent-sanctions-001")
                o.stringValue("entityType", "INDIVIDUAL")
                o.stringValue("name", "Agent Pact Probe Person")
                o.stringValue("status", "POTENTIAL_HIT")
                o.decimalType("overallScore", 0.9)
                o.array("matches")
                o.array("checkedLists") { a -> a.stringValue("OFAC_SDN") }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sanctions-service")
    fun listPendingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a sanctions check exists for the pact agent probe")
        .uponReceiving("GET the pending queue behind the sanctions_list_pending tool")
        .path("/api/v1/sanctions/pending")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // listPending() is findByStatus(POTENTIAL_HIT): the FILTER guarantees every element's
            // status, so unlike the unfiltered list leg this value pin asserts something real.
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                stringType("name")
                stringValue("status", "POTENTIAL_HIT")
                decimalType("overallScore")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sanctions-service")
    fun getCheckMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no sanctions check exists with the requested id")
        .uponReceiving("GET a check sanctions-service does not hold, via the sanctions_get_check tool")
        .path("/api/v1/sanctions/6e6e6e6e-6e6e-4e6e-8e6e-6e6e6e6e6e6e")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listChecksPact")
    fun `sanctions_list_checks returns a bare array of checks`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("sanctions_list_checks", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("id").isTextual).isTrue()
        assertThat(result[0].get("status").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getCheckPact")
    fun `sanctions_get_check returns the seeded check verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "sanctions_get_check",
            args("""{"id": "5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d"}"""),
        )
        assertThat(result.get("id").asText()).isEqualTo("5d5d5d5d-5d5d-4d5d-8d5d-5d5d5d5d5d5d")
        assertThat(result.get("status").asText()).isEqualTo("POTENTIAL_HIT")
        assertThat(result.get("name").asText()).isEqualTo("Agent Pact Probe Person")
    }

    @Test
    @PactTestFor(pactMethod = "listPendingPact")
    fun `sanctions_list_pending returns only potential hits`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("sanctions_list_pending", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        result.forEach { assertThat(it.get("status").asText()).isEqualTo("POTENTIAL_HIT") }
    }

    @Test
    @PactTestFor(pactMethod = "getCheckMissingPact")
    fun `an unknown check fails closed instead of returning a made-up verdict`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read(
                "sanctions_get_check",
                args("""{"id": "6e6e6e6e-6e6e-4e6e-8e6e-6e6e6e6e6e6e"}"""),
            )
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its sanctions client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { sanctionsClient = sanctionsClientFor(mockServer) }

    private fun sanctionsClientFor(mockServer: MockServer): SanctionsServiceClient = object : SanctionsServiceClient {
        override fun listChecks(): JsonNode = get(mockServer, "/api/v1/sanctions")

        override fun getCheck(id: String): JsonNode = get(mockServer, "/api/v1/sanctions/$id")

        override fun listPending(): JsonNode = get(mockServer, "/api/v1/sanctions/pending")

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
