// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.contract

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
import com.openbank.agent.infrastructure.client.DisputeServiceClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the assistant's dispute read tools against dispute-service
 * (`DisputeServiceClient`, dispatched by [RestDownstreamReadAdapter] as `dispute_list` /
 * `dispute_get` / `dispute_list_by_account` / `dispute_get_timeline`). The model receives the
 * downstream body VERBATIM, so the contract pins the tool-name-to-endpoint mapping and that an
 * unknown dispute id FAILS CLOSED as an exception rather than a synthesised empty dispute — a
 * made-up dispute state is a made-up chargeback claim.
 *
 * Provider verification lives in dispute's DisputePactProviderVerificationTest (the service's
 * FIRST provider-side pact class), seeded by AgentDisputePactSeed: one UNDER_REVIEW dispute with
 * one timeline event. List legs pin per-row SHAPE via arrayEachLike (never count or order); the
 * get leg pins identity fields by value because get returns exactly the seeded row.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-dispute-service", pactVersion = PactSpecVersion.V3)
class AgentDisputeReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-dispute-service")
    fun listPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a dispute exists for the pact agent")
        .uponReceiving("GET the dispute list behind the dispute_list tool")
        .path("/api/v1/disputes")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // list serialises a BARE array of Dispute DTOs, so the type matchers hold for every
            // row the provider test's other seeds leave behind.
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                stringType("reference")
                stringType("disputeType")
                stringType("status")
                decimalType("amount")
                stringType("currency")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-dispute-service")
    fun getPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a dispute exists for the pact agent")
        .uponReceiving("GET the dispute behind the dispute_get tool")
        .path("/api/v1/disputes/1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Pinned to AgentDisputePactSeed's exact row: get returns that one dispute, so the
            // identity fields are pinned by value, not just by type.
            newJsonBody { o ->
                o.stringValue("id", "1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d")
                o.stringValue("reference", "PACT-AGENT-DISP-001")
                o.stringValue("transactionId", "2d2d2d2d-2d2d-4d2d-8d2d-2d2d2d2d2d2d")
                o.stringValue("accountId", "3d3d3d3d-3d3d-4d3d-8d3d-3d3d3d3d3d3d")
                o.stringValue("disputeType", "DUPLICATE")
                o.stringValue("status", "UNDER_REVIEW")
                o.stringValue("resolution", "PENDING")
                o.decimalType("amount", 250.0)
                o.stringValue("currency", "EUR")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-dispute-service")
    fun listByAccountPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a dispute exists for the pact agent")
        .uponReceiving("GET the account disputes behind the dispute_list_by_account tool")
        .path("/api/v1/disputes/account/3d3d3d3d-3d3d-4d3d-8d3d-3d3d3d3d3d3d")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                stringType("reference")
                stringType("status")
                decimalType("amount")
                stringType("currency")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-dispute-service")
    fun getTimelinePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a dispute exists for the pact agent")
        .uponReceiving("GET the dispute timeline behind the dispute_get_timeline tool")
        .path("/api/v1/disputes/1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d/timeline")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                uuid("disputeId")
                stringType("eventType")
                stringType("description")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-dispute-service")
    fun getMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no dispute exists with the requested id")
        .uponReceiving("GET a dispute dispute-service does not hold, via the dispute_get tool")
        .path("/api/v1/disputes/abababab-abab-4aba-8aba-babababababa")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listPact")
    fun `dispute_list returns a bare array of disputes`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("dispute_list", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("id").isTextual).isTrue()
        assertThat(result[0].get("status").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getPact")
    fun `dispute_get returns the seeded dispute verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "dispute_get",
            args("""{"disputeId": "1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d"}"""),
        )
        assertThat(result.get("id").asText()).isEqualTo("1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d")
        assertThat(result.get("reference").asText()).isEqualTo("PACT-AGENT-DISP-001")
        assertThat(result.get("status").asText()).isEqualTo("UNDER_REVIEW")
    }

    @Test
    @PactTestFor(pactMethod = "listByAccountPact")
    fun `dispute_list_by_account returns a bare array of disputes`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "dispute_list_by_account",
            args("""{"accountId": "3d3d3d3d-3d3d-4d3d-8d3d-3d3d3d3d3d3d"}"""),
        )
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        // Typ matchers generate the mock's example values — assert shape, not the pinned UUID.
        assertThat(result[0].get("id").isTextual).isTrue()
        assertThat(result[0].get("amount").isNumber).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getTimelinePact")
    fun `dispute_get_timeline returns a bare array of events`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "dispute_get_timeline",
            args("""{"disputeId": "1d1d1d1d-1d1d-4d1d-8d1d-1d1d1d1d1d1d"}"""),
        )
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("disputeId").isTextual).isTrue()
        assertThat(result[0].get("eventType").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getMissingPact")
    fun `an unknown dispute fails closed instead of returning a made-up dispute`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read(
                "dispute_get",
                args("""{"disputeId": "abababab-abab-4aba-8aba-babababababa"}"""),
            )
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its dispute client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { disputeClient = disputeClientFor(mockServer) }

    private fun disputeClientFor(mockServer: MockServer): DisputeServiceClient = object : DisputeServiceClient {
        override fun list(status: String?): JsonNode = get(
            mockServer,
            if (status == null) "/api/v1/disputes" else "/api/v1/disputes?status=$status",
        )

        override fun get(id: String): JsonNode = get(mockServer, "/api/v1/disputes/$id")

        override fun listByAccount(accountId: String): JsonNode = get(mockServer, "/api/v1/disputes/account/$accountId")

        override fun getTimeline(id: String): JsonNode = get(mockServer, "/api/v1/disputes/$id/timeline")

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
