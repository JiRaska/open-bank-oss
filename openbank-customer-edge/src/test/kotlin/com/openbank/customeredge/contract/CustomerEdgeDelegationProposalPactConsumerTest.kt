// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.CustomerMcpProposal
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID

/**
 * Consumer-driven contract for customer-edge's calls to delegation-service's payment-proposal store
 * (ADR-0383 D6): read one proposal, approve it (hash CAS), record its execution, and reject it.
 *
 * Like every other edge contract, the request side is produced by the REAL production handler
 * (`getMcpProposal`, `approveMcpProposal`, `rejectMcpProposal`) through the REAL [UpstreamClient],
 * while every expected path below is a LITERAL (#2290). The edge builds these URLs by hand with no
 * `@Path` anywhere, so only this pact plus the provider replay can see a wrong route; a mocked
 * `UpstreamClient` (as in `McpProposalApprovalTest`) only proves the edge agrees with itself.
 *
 * `payloadSha256` is PINNED with `stringValue`, not type-matched: the edge recomputes the canonical
 * hash from the returned instruction and refuses on any difference, so the replay asserting the
 * provider's real hash for the seeded instruction equals the edge's is the cross-service check that
 * the two `canonicalSha256` implementations agree.
 *
 * Provider replay: delegation-service's `DelegationProposalPactFolderProviderVerificationTest`
 * (`@PactFolder`, runs on every PR). Its `@State` seeds and the fixed values below must stay in step.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-delegation-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeDelegationProposalPactConsumerTest {

    private companion object {
        // Fixed values — must match the @State seed in delegation-service's provider verification.
        const val PROPOSAL_ID = "c0ffee00-1111-4222-8333-444455556666"
        const val PARTY_ID = "c0ffee00-2222-4333-8444-555566667777"
        const val DEBTOR_ACCOUNT_ID = "c0ffee00-3333-4444-8555-666677778888"
        const val PAYMENT_ID = "c0ffee00-4444-4555-8666-777788889999"
        const val MCP_CLIENT = "mcp-hosted-reference"
        const val IBAN = "CZ6508000000192000145399"
        const val CREDITOR = "Elektrarna Praha a.s."
        const val AMOUNT = "1250.50"
        const val CURRENCY = "CZK"
        const val SCA_CHALLENGE = "c0ffee00-5555-4666-8777-88889999aaaa"

        const val PENDING = "a PENDING payment proposal exists for the customer"
        const val APPROVED = "an APPROVED payment proposal exists for the customer"

        val HASH: String = CustomerMcpProposal.canonicalSha256(
            UUID.fromString(PROPOSAL_ID),
            UUID.fromString(PARTY_ID),
            "DOMESTIC_PAYMENT",
            UUID.fromString(DEBTOR_ACCOUNT_ID),
            IBAN,
            CREDITOR,
            AMOUNT,
            CURRENCY,
            null,
        )
        const val ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})"
    }

    /**
     * Loopback stand-in for Keycloak's token endpoint AND for the services the approval path calls
     * besides delegation (account ownership, party name, the SCA consume, the domestic payment).
     * None of those are under contract here — each has its own pact — but the production approve
     * handler must reach the store calls for this contract to be exercised at all.
     */
    private lateinit var stubServer: HttpServer
    private val stubbedPaths = mutableListOf<String>()

    @BeforeEach
    fun startStubServer() {
        stubServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                synchronized(stubbedPaths) { stubbedPaths += "${exchange.requestMethod} $path" }
                val (status, body) = when {
                    path.endsWith("/protocol/openid-connect/token") ->
                        200 to """{"access_token":"pact-token","expires_in":300}"""
                    path.startsWith("/api/v1/accounts/") ->
                        200 to """{"id":"$DEBTOR_ACCOUNT_ID","partyId":"$PARTY_ID","accountNumber":"$IBAN"}"""
                    path.startsWith("/api/v1/parties/") ->
                        200 to """{"legalName":"Pact Customer"}"""
                    path.contains("/sca/challenges/") ->
                        200 to """{"status":"CONSUMED"}"""
                    path.endsWith("/domestic-payments") -> 201 to """{"id":"$PAYMENT_ID","status":"RECEIVED"}"""
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stopStubServer() {
        stubServer.stop(0)
    }

    private fun edgeResource(delegationBaseUrl: String): CustomerEdgeResource {
        val stub = "http://127.0.0.1:${stubServer.address.port}"
        val upstream = UpstreamClient().apply {
            tlsConfigurationName = java.util.Optional.empty()
            tokenEndpointBase = stub
            clientId = "openbank-edge"
            clientSecret = "pact"
            tlsTrustCertificateFile = java.util.Optional.empty()
        }
        return CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            inMemoryPaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            // The customer's own bank-app session: the only caller the proposal routes admit.
            jwt = mockk(relaxed = true) {
                every { getClaim<String>("party_id") } returns PARTY_ID
                every { subject } returns PARTY_ID
                every { getClaim<Any?>("azp") } returns "openbank-app"
                every { containsClaim("act") } returns false
                every { audience } returns setOf("openbank-edge")
            }
            objectMapper = ObjectMapper()
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            delegationServiceUrl = delegationBaseUrl
            accountServiceUrl = stub
            partyServiceUrl = stub
            scaServiceUrl = stub
            domesticPaymentServiceUrl = stub
            sepaPaymentServiceUrl = stub
        }
    }

    private fun LambdaDslJsonBody.proposal(status: String) {
        stringValue("id", PROPOSAL_ID)
        stringValue("partyId", PARTY_ID)
        stringValue("status", status)
        stringValue("mcpClientId", MCP_CLIENT)
        stringValue("operationType", "DOMESTIC_PAYMENT")
        stringValue("debtorAccountId", DEBTOR_ACCOUNT_ID)
        stringValue("creditorIban", IBAN)
        stringValue("creditorName", CREDITOR)
        stringValue("amount", AMOUNT)
        stringValue("currency", CURRENCY)
        stringValue("payloadSha256", HASH)
        stringValue("approvalRequestId", "mcp-proposal:$PROPOSAL_ID")
        stringMatcher("expiresAt", ISO_INSTANT, "2099-01-01T00:00:00Z")
    }

    private fun PactDslWithState.readPending() = this
        .uponReceiving("GET one payment proposal scoped to its customer")
        .path("/api/v1/payment-proposals/$PROPOSAL_ID")
        .query("partyId=$PARTY_ID")
        .method("GET")
        .headers(mapOf("X-Customer-Party-Id" to PARTY_ID))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { it.proposal("PENDING") }.build())

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-delegation-service")
    fun readProposalPact(builder: PactDslWithProvider): RequestResponsePact =
        builder.given(PENDING).readPending().toPact()

    @Test
    @PactTestFor(pactMethod = "readProposalPact")
    fun `the edge reads one proposal through the party-scoped route`(mockServer: MockServer) {
        val resp = edgeResource(mockServer.getUrl()).getMcpProposal(UUID.fromString(PROPOSAL_ID))
        assertThat(resp.status).isEqualTo(200)
        val parsed = CustomerMcpProposal.parse(ObjectMapper(), resp.entity as String)
        assertThat(parsed).describedAs("the contracted body must parse as a proposal").isNotNull()
        assertThat(parsed!!.canonicalSha256()).isEqualTo(HASH)
    }

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-delegation-service")
    fun approveProposalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PENDING).readPending()
        .given(PENDING)
        .uponReceiving("POST approve a payment proposal with its payload hash")
        .path("/api/v1/payment-proposals/$PROPOSAL_ID/approve")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "mcp-proposal-approve-$PROPOSAL_ID-$SCA_CHALLENGE")
        .body(
            newJsonBody { o ->
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("payloadSha256", HASH)
            }.build(),
        )
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { it.proposal("APPROVED") }.build())
        .given(APPROVED)
        .uponReceiving("POST record the execution of an approved payment proposal")
        .path("/api/v1/payment-proposals/$PROPOSAL_ID/execution")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "mcp-proposal-execution-$PROPOSAL_ID-$PAYMENT_ID")
        .body(
            newJsonBody { o ->
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("payloadSha256", HASH)
                o.stringValue("paymentId", PAYMENT_ID)
            }.build(),
        )
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.proposal("EXECUTED")
                o.stringValue("executedPaymentId", PAYMENT_ID)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "approveProposalPact")
    fun `the edge approves then records execution through the contracted routes`(mockServer: MockServer) {
        val resp = edgeResource(mockServer.getUrl())
            .approveMcpProposal(UUID.fromString(PROPOSAL_ID), """{"payloadSha256":"$HASH"}""", SCA_CHALLENGE)
        assertThat(resp.status).describedAs("stub calls: $stubbedPaths").isEqualTo(201)
    }

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-delegation-service")
    fun rejectProposalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(PENDING)
        .uponReceiving("POST reject a payment proposal")
        .path("/api/v1/payment-proposals/$PROPOSAL_ID/reject")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "X-Customer-Party-Id" to PARTY_ID))
        .matchHeader("Idempotency-Key", ".+", "mcp-proposal-reject-$PROPOSAL_ID")
        .body(newJsonBody { o -> o.stringValue("partyId", PARTY_ID) }.build())
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { it.proposal("REJECTED") }.build())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectProposalPact")
    fun `the edge rejects through the contracted route`(mockServer: MockServer) {
        val resp = edgeResource(mockServer.getUrl()).rejectMcpProposal(UUID.fromString(PROPOSAL_ID))
        assertThat(resp.status).isEqualTo(200)
    }
}
