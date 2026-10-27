// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.infrastructure.client.AmlServiceClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the assistant's AML case read tools against aml-service
 * (`AmlServiceClient`, dispatched by [RestDownstreamReadAdapter] as `aml_list_cases` /
 * `aml_get_case`). The endpoints exist and the client paths match (verified before writing a
 * single matcher — the lesson of the get_balance_holds dead tool, #13071). What was unpinned is
 * the tool-to-endpoint mapping, the query-parameter wiring of the list (status/partyId/limit/
 * offset), and the fail-closed shape for an unknown case — on a financial-crime boundary the
 * assistant must never invent a case.
 *
 * Provider verification lives in aml-service's AmlPactProviderVerificationTest: the 200 legs
 * reuse its `an AML case exists for the pact party` seed (constants below must match its
 * companion and the broker twin's), the 404 leg uses `no AML case exists with the requested id`
 * — both required because this provider suite's class-level @PactFilter drops stateless
 * interactions.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-aml-service", pactVersion = PactSpecVersion.V3)
class AgentAmlReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-aml-service")
    fun getCasePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an AML case exists for the pact party")
        .uponReceiving("GET the AML case behind the aml_get_case tool")
        .path("/api/v1/aml/cases/$CASE_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Only the fields the model names a case by; the provider may carry more.
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("partyId", UUID.fromString(PACT_PARTY_ID))
                o.stringValue("idempotencyKey", IDEMPOTENCY_KEY)
                o.stringValue("customerReference", CUSTOMER_REFERENCE)
                o.stringValue("screeningType", "TRANSACTION_MONITORING")
                o.stringValue("riskLevel", "HIGH")
                o.stringValue("status", "OPEN")
                o.stringValue("alertCode", "PACT-ALERT-001")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-aml-service")
    fun listCasesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an AML case exists for the pact party")
        .uponReceiving("GET the AML cases of the pact party behind the aml_list_cases tool")
        .path("/api/v1/aml/cases")
        .method("GET")
        .query("partyId=$PACT_PARTY_ID&limit=20&offset=0")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonArray { arr ->
                arr.`object` { o ->
                    o.uuid("id")
                    o.uuid("partyId", UUID.fromString(PACT_PARTY_ID))
                    o.stringValue("customerReference", CUSTOMER_REFERENCE)
                    o.stringValue("status", "OPEN")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-aml-service")
    fun getCaseMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no AML case exists with the requested id")
        .uponReceiving("GET an AML case id aml-service does not hold, via the aml_get_case tool")
        .path("/api/v1/aml/cases/$MISSING_CASE_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getCasePact")
    fun `aml_get_case returns the case verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("aml_get_case", args("""{"caseId": "$CASE_ID"}"""))
        assertThat(result.get("partyId").asText()).isEqualTo(PACT_PARTY_ID)
        assertThat(result.get("customerReference").asText()).isEqualTo(CUSTOMER_REFERENCE)
        assertThat(result.get("status").asText()).isEqualTo("OPEN")
        // Untouched fields survive the pass-through to the model.
        assertThat(result.get("screeningType").asText()).isEqualTo("TRANSACTION_MONITORING")
    }

    @Test
    @PactTestFor(pactMethod = "listCasesPact")
    fun `aml_list_cases wires the partyId filter through to the query string`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "aml_list_cases",
            args("""{"partyId": "$PACT_PARTY_ID", "limit": 20}"""),
        )
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("partyId").asText()).isEqualTo(PACT_PARTY_ID)
    }

    @Test
    @PactTestFor(pactMethod = "getCaseMissingPact")
    fun `an unknown case fails closed instead of returning an empty case`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read("aml_get_case", args("""{"caseId": "$MISSING_CASE_ID"}"""))
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its aml client bound to the pact mock —
     * the tool-name dispatch, the query wiring and the verbatim pass-through are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { amlClient = amlClientFor(mockServer) }

    private fun amlClientFor(mockServer: MockServer): AmlServiceClient = object : AmlServiceClient {
        override fun listCases(status: String?, partyId: String?, limit: Int, offset: Int): JsonNode {
            val request = given().baseUri(mockServer.getUrl()).contentType("application/json")
            status?.let { request.queryParam("status", it) }
            partyId?.let { request.queryParam("partyId", it) }
            return get(request.queryParam("limit", limit).queryParam("offset", offset), "/api/v1/aml/cases")
        }

        override fun getCase(caseId: String): JsonNode =
            get(given().baseUri(mockServer.getUrl()).contentType("application/json"), "/api/v1/aml/cases/$caseId")

        private fun get(request: io.restassured.specification.RequestSpecification, path: String): JsonNode {
            val response = request.get(path)
            if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
            return mapper.readTree(response.asString())
        }
    }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private companion object {
        val mapper = jacksonObjectMapper()

        /** Must match AmlPactProviderVerificationTest's companion and the broker twin. */
        const val CASE_ID = "2a2a2a2a-2a2a-4a2a-8a2a-2a2a2a2a2a2a"
        const val PACT_PARTY_ID = "1b1b1b1b-1b1b-4b1b-8b1b-1b1b1b1b1b1b"
        const val IDEMPOTENCY_KEY = "pact-agent-aml-001"
        const val CUSTOMER_REFERENCE = "pact-agent-aml-ref"

        /** Deliberately never seeded on the provider side — the 404 leg leans on that. */
        const val MISSING_CASE_ID = "3c3c3c3c-3c3c-4c3c-8c3c-3c3c3c3c3c3c"
    }
}
