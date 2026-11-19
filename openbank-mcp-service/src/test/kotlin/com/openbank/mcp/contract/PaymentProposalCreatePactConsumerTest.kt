// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.mcp.infrastructure.customer.CreatePaymentProposalRequest
import com.openbank.mcp.infrastructure.customer.CustomerPaymentProposer
import com.openbank.mcp.infrastructure.customer.GrantKey
import com.openbank.mcp.infrastructure.customer.McpProposalReceipt
import com.openbank.mcp.infrastructure.customer.PaymentProposalStoreClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract for the ADR-0383 D6 proposal store: delegation-service
 * `POST /api/v1/payment-proposals`, the ONE store route mcp-service may call. Committed to
 * `pacts/openbank-mcp-service-openbank-delegation-service.json`, the file delegation-service's
 * `@Consumer("openbank-mcp-service")` `@PactFolder` replay reads in full.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests"). Two interactions: a PENDING proposal created under an active grant,
 * and the 403 the store answers when no active grant permits it (expired, revoked, or never made) —
 * the refusal the tool maps to "Not permitted" and a dropped grant cache entry.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-delegation-service", pactVersion = PactSpecVersion.V3)
class PaymentProposalCreatePactConsumerTest {

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    private fun request(builder: PactDslWithProvider, state: String, description: String) = builder
        .given(state)
        .uponReceiving(description)
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .matchHeader(IDEMPOTENCY_HEADER, "^[0-9a-f]{64}$", IDEMPOTENCY_KEY)
        .body(mapper.writeValueAsString(REQUEST))

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun createdPact(builder: PactDslWithProvider): RequestResponsePact =
        request(builder, STATE_ACTIVE, "POST create a PENDING payment proposal under an active propose grant")
            .willRespondWith()
            .status(201)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(
                newJsonBody { o ->
                    o.uuid("proposalId", UUID.fromString(PROPOSAL_ID))
                    // Value, not type: the tool refuses any state but PENDING.
                    o.stringValue("status", "PENDING")
                    o.booleanValue("deduplicated", false)
                    o.datetime("expiresAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                    o.stringType("summary", "Proposed 125.50 CZK")
                }.build(),
            )
            .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun notPermittedPact(builder: PactDslWithProvider): RequestResponsePact =
        request(builder, STATE_NONE, "POST create a payment proposal with no active propose grant")
            .willRespondWith()
            .status(403)
            .toPact()

    @Test
    @PactTestFor(pactMethod = "createdPact")
    fun `the store answers a PENDING receipt the client can read`(mockServer: MockServer) {
        val body = post(mockServer).then().statusCode(201).extract().asString()
        val receipt = mapper.readValue<McpProposalReceipt>(body)
        assertThat(receipt.proposalId).isEqualTo(UUID.fromString(PROPOSAL_ID))
        assertThat(receipt.status).isEqualTo(CustomerPaymentProposer.PENDING)
        assertThat(receipt.deduplicated).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "notPermittedPact")
    fun `no active grant is a 403`(mockServer: MockServer) {
        post(mockServer).then().statusCode(403)
    }

    private fun post(mockServer: MockServer) = run {
        assertThat(clientDerivedPath())
            .describedAs("PaymentProposalStoreClient's @Path drifted from the pact").isEqualTo(EXPECTED_PATH)
        given().baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header(IDEMPOTENCY_HEADER, IDEMPOTENCY_KEY)
            .body(mapper.writeValueAsString(REQUEST))
            .post(clientDerivedPath())
    }

    companion object {
        const val CONSUMER = "openbank-mcp-service"
        const val PROVIDER = "openbank-delegation-service"
        const val STATE_ACTIVE = "an active MCP_CLIENT propose grant exists for the pact party, client, key and account"
        const val STATE_NONE = "no MCP_CLIENT propose grant exists for the pact party, client, key and account"
        const val PARTY_ID = "d3d3d3d3-0000-4000-8000-000000000001"
        const val ACCOUNT_ID = "d3d3d3d3-0000-4000-8000-000000000003"
        const val PROPOSAL_ID = "d3d3d3d3-0000-4000-8000-000000000004"
        const val CLIENT_ID = "mcp-local-reference"
        const val JKT = "0000000000000000000000000000000000000000000"
        const val IDEMPOTENCY_HEADER = PaymentProposalStoreClient.IDEMPOTENCY_KEY_HEADER

        /** LITERAL, retyped from delegation-service's `PaymentProposalResource`. */
        const val EXPECTED_PATH = "/api/v1/payment-proposals"

        val IDEMPOTENCY_KEY: String = CustomerPaymentProposer.idempotencyKey(
            GrantKey(UUID.fromString(PARTY_ID), CLIENT_ID, JKT),
            null,
            "pact-request-0001",
        )

        val REQUEST = CreatePaymentProposalRequest(
            partyId = UUID.fromString(PARTY_ID),
            mcpClientId = CLIENT_ID,
            keyThumbprint = JKT,
            operationType = "DOMESTIC_PAYMENT",
            debtorAccountId = UUID.fromString(ACCOUNT_ID),
            creditorIban = "CZ6508000000192000145399",
            creditorName = "Pact Creditor",
            amount = "125.50",
            currency = "CZK",
            reference = "Pact reference",
        )

        fun clientDerivedPath(): String = PaymentProposalStoreClient::class.java.getAnnotation(Path::class.java).value
    }
}
