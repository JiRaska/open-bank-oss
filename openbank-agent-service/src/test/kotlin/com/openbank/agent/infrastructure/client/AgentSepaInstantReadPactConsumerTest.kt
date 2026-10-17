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
 * Consumer-driven contract for the assistant's SCT Inst read tools against sepa-instant
 * (`SepaInstantServiceClient`, dispatched by [RestDownstreamReadAdapter] as `sepa_instant_list` /
 * `sepa_instant_get` / `sepa_instant_list_by_debtor`). The model receives the downstream body
 * VERBATIM, so the contract pins the tool-name-to-endpoint mapping and that an unknown payment id
 * FAILS CLOSED as an exception rather than a synthesised empty payment — a made-up SCT Inst
 * payment is a made-up money-movement claim.
 *
 * Provider verification lives in sepa-instant's SepaInstantPactProviderVerificationTest (the
 * service's FIRST provider-side pact class), seeded by AgentSepaInstantPactSeed: one SETTLED
 * payment. List legs pin per-row SHAPE via arrayEachLike (never count or order); the get leg pins
 * identity fields by value because getById returns exactly the seeded row.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sepa-instant-service", pactVersion = PactSpecVersion.V3)
class AgentSepaInstantReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sepa-instant-service")
    fun listPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a settled SCT Inst payment exists for the pact agent")
        .uponReceiving("GET the payment list behind the sepa_instant_list tool")
        .path("/api/v1/sepa-instant")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // listAll serialises a BARE array of SctInstPaymentResponse DTOs, so the type
            // matchers hold for every row the provider test's other seeds leave behind.
            PactDslJsonArray.arrayEachLike().apply {
                uuid("paymentId")
                stringType("status")
                stringType("debtorIban")
                decimalType("amount")
                stringType("currency")
                stringType("endToEndId")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sepa-instant-service")
    fun getPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a settled SCT Inst payment exists for the pact agent")
        .uponReceiving("GET the payment behind the sepa_instant_get tool")
        .path("/api/v1/sepa-instant/6a6a6a6a-6a6a-4a6a-8a6a-6a6a6a6a6a6a")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Pinned to AgentSepaInstantPactSeed's exact row: getById returns that one payment,
            // so the identity fields are pinned by value, not just by type.
            newJsonBody { o ->
                o.stringValue("paymentId", "6a6a6a6a-6a6a-4a6a-8a6a-6a6a6a6a6a6a")
                o.stringValue("status", "SETTLED")
                o.stringValue("debtorIban", "CZ6508000000192000145399")
                o.stringValue("creditorIban", "DE89370400440532013000")
                o.decimalType("amount", 100.0)
                o.stringValue("currency", "EUR")
                o.stringValue("endToEndId", "PACT-E2E-SCTINST-001")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sepa-instant-service")
    fun listByDebtorPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a settled SCT Inst payment exists for the pact agent")
        .uponReceiving("GET the debtor payments behind the sepa_instant_list_by_debtor tool")
        .path("/api/v1/sepa-instant/debtor/3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a")
        .query("page=0&size=20")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            PactDslJsonArray.arrayEachLike().apply {
                uuid("paymentId")
                stringType("status")
                decimalType("amount")
                stringType("currency")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-sepa-instant-service")
    fun getMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no SCT Inst payment exists with the requested id")
        .uponReceiving("GET a payment sepa-instant does not hold, via the sepa_instant_get tool")
        .path("/api/v1/sepa-instant/abababab-abab-4aba-8aba-babababababa")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listPact")
    fun `sepa_instant_list returns a bare array of payments`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("sepa_instant_list", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("paymentId").isTextual).isTrue()
        assertThat(result[0].get("status").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getPact")
    fun `sepa_instant_get returns the seeded payment verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "sepa_instant_get",
            args("""{"paymentId": "6a6a6a6a-6a6a-4a6a-8a6a-6a6a6a6a6a6a"}"""),
        )
        assertThat(result.get("paymentId").asText()).isEqualTo("6a6a6a6a-6a6a-4a6a-8a6a-6a6a6a6a6a6a")
        assertThat(result.get("status").asText()).isEqualTo("SETTLED")
        assertThat(result.get("endToEndId").asText()).isEqualTo("PACT-E2E-SCTINST-001")
    }

    @Test
    @PactTestFor(pactMethod = "listByDebtorPact")
    fun `sepa_instant_list_by_debtor returns a bare array of payments`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "sepa_instant_list_by_debtor",
            args("""{"debtorAccountId": "3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a"}"""),
        )
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        // Typ matchers generate the mock's example values — assert shape, not the pinned UUID.
        assertThat(result[0].get("paymentId").isTextual).isTrue()
        assertThat(result[0].get("amount").isNumber).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getMissingPact")
    fun `an unknown payment fails closed instead of returning a made-up payment`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read(
                "sepa_instant_get",
                args("""{"paymentId": "abababab-abab-4aba-8aba-babababababa"}"""),
            )
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its sepa-instant client bound to the pact
     * mock — the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { sepaInstantClient = sepaInstantClientFor(mockServer) }

    private fun sepaInstantClientFor(mockServer: MockServer): SepaInstantServiceClient =
        object : SepaInstantServiceClient {
            override fun listPayments(): JsonNode = get(mockServer, "/api/v1/sepa-instant")

            override fun getPayment(paymentId: String): JsonNode = get(mockServer, "/api/v1/sepa-instant/$paymentId")

            override fun listByDebtor(debtorAccountId: String, page: Int, size: Int): JsonNode =
                get(mockServer, "/api/v1/sepa-instant/debtor/$debtorAccountId?page=$page&size=$size")

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
