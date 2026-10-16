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
 * Consumer-driven contract for the assistant's clearing read tools against clearing-service
 * (`ClearingServiceClient`, dispatched by [RestDownstreamReadAdapter] as `clearing_list_batches` /
 * `clearing_get_batch` / `clearing_get_batch_items`). The model receives the downstream body
 * VERBATIM, so the contract pins the tool-name-to-endpoint mapping and that an unknown batch id
 * FAILS CLOSED as an exception rather than a synthesised empty batch — a made-up clearing state
 * is a made-up settlement claim.
 *
 * Provider verification lives in clearing's ClearingPactProviderVerificationTest (the service's
 * FIRST provider-side pact class — until now nothing consumed clearing over a contract), seeded
 * by AgentClearingPactSeed: one SETTLED batch with one SETTLED item. List legs pin per-row SHAPE
 * via arrayEachLike (never count or order); the get leg pins identity fields by value because
 * getBatch returns exactly the seeded row.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-clearing-service", pactVersion = PactSpecVersion.V3)
class AgentClearingReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-clearing-service")
    fun listBatchesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a clearing batch with one item exists for the pact agent")
        .uponReceiving("GET the batch list behind the clearing_list_batches tool")
        .path("/api/v1/clearing/batches")
        .query("page=0&size=20")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // listBatches serialises a BARE array. Every element is a ClearingBatch DTO, so the
            // type matchers hold for every row the provider test's other seeds leave behind.
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                stringType("batchReference")
                stringType("rail")
                stringType("status")
                decimalType("totalDebit")
                stringType("currency")
                numberType("itemCount")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-clearing-service")
    fun getBatchPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a clearing batch with one item exists for the pact agent")
        .uponReceiving("GET the batch behind the clearing_get_batch tool")
        .path("/api/v1/clearing/batches/7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Pinned to AgentClearingPactSeed's exact row: getBatch returns that one batch, so the
            // identity fields are pinned by value, not just by type.
            newJsonBody { o ->
                o.stringValue("id", "7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a")
                o.stringValue("batchReference", "PACT-AGENT-BATCH-001")
                o.stringValue("rail", "SEPA_SCT")
                o.stringValue("status", "SETTLED")
                o.stringValue("currency", "EUR")
                o.numberValue("itemCount", 1)
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-clearing-service")
    fun getBatchItemsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a clearing batch with one item exists for the pact agent")
        .uponReceiving("GET the batch items behind the clearing_get_batch_items tool")
        .path("/api/v1/clearing/batches/7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a/items")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            PactDslJsonArray.arrayEachLike().apply {
                uuid("id")
                uuid("batchId")
                stringType("paymentReference")
                decimalType("amount")
                stringType("currency")
                stringType("status")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-clearing-service")
    fun getBatchMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no clearing batch exists with the requested id")
        .uponReceiving("GET a batch clearing-service does not hold, via the clearing_get_batch tool")
        .path("/api/v1/clearing/batches/abababab-abab-4aba-8aba-babababababa")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listBatchesPact")
    fun `clearing_list_batches returns a bare array of batches`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("clearing_list_batches", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("id").isTextual).isTrue()
        assertThat(result[0].get("status").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getBatchPact")
    fun `clearing_get_batch returns the seeded batch verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "clearing_get_batch",
            args("""{"batchId": "7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a"}"""),
        )
        assertThat(result.get("id").asText()).isEqualTo("7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a")
        assertThat(result.get("batchReference").asText()).isEqualTo("PACT-AGENT-BATCH-001")
        assertThat(result.get("status").asText()).isEqualTo("SETTLED")
    }

    @Test
    @PactTestFor(pactMethod = "getBatchItemsPact")
    fun `clearing_get_batch_items returns a bare array of items`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "clearing_get_batch_items",
            args("""{"batchId": "7a7a7a7a-7a7a-4a7a-8a7a-7a7a7a7a7a7a"}"""),
        )
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        // Typ matchers generate the mock's example values — assert shape, not the pinned UUID.
        assertThat(result[0].get("batchId").isTextual).isTrue()
        assertThat(result[0].get("amount").isNumber).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getBatchMissingPact")
    fun `an unknown batch fails closed instead of returning a made-up batch`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read(
                "clearing_get_batch",
                args("""{"batchId": "abababab-abab-4aba-8aba-babababababa"}"""),
            )
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its clearing client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { clearingClient = clearingClientFor(mockServer) }

    private fun clearingClientFor(mockServer: MockServer): ClearingServiceClient = object : ClearingServiceClient {
        override fun listBatches(status: String?, page: Int, size: Int): JsonNode = get(
            mockServer,
            if (status == null) {
                "/api/v1/clearing/batches?page=$page&size=$size"
            } else {
                "/api/v1/clearing/batches?status=$status&page=$page&size=$size"
            },
        )

        override fun getBatch(id: String): JsonNode = get(mockServer, "/api/v1/clearing/batches/$id")

        override fun getBatchItems(id: String): JsonNode = get(mockServer, "/api/v1/clearing/batches/$id/items")

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
