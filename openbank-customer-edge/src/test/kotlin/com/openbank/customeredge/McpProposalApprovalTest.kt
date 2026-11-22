// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.CustomerMcpProposal
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0383 D6 at the edge. Order is the control: the customer's challenge is spent, THEN the store
 * records APPROVED (CAS), and only then is a payment created. A store refusal after SCA sends no
 * payment; a payment failure leaves the proposal retryable and a retry pays at most once.
 */
class McpProposalApprovalTest {
    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-11-19T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun hash(type: String = "DOMESTIC_PAYMENT", iban: String = CZ, currency: String = "CZK") =
        CustomerMcpProposal.canonicalSha256(id, party, type, account, iban, NAME, "1250.50", currency, null)

    private val audit: EdgeAuditPublisher = mockk(relaxed = true)

    private fun resource(
        up: UpstreamClient,
        azp: String = "openbank-app",
        act: Boolean = false,
        aud: Set<String> = setOf("openbank-edge"),
    ) = CustomerEdgeResource(
        up,
        audit,
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        clock,
    ).apply {
        jwt = mockk(relaxed = true) {
            every { getClaim<String>("party_id") } returns party.toString()
            every { subject } returns party.toString()
            every { getClaim<Any?>("azp") } returns azp
            every { containsClaim("act") } returns act
            every { audience } returns aud
        }
        objectMapper = ObjectMapper()
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        accountServiceUrl = "http://account"
        domesticPaymentServiceUrl = "http://dompay"
        sepaPaymentServiceUrl = "http://sepa"
        partyServiceUrl = "http://party"
        scaServiceUrl = "http://sca"
        delegationServiceUrl = "http://delegation"
    }

    private fun proposalJson(
        status: String = "PENDING",
        expiresAt: Instant = now.plusSeconds(3600),
        type: String = "DOMESTIC_PAYMENT",
        iban: String = CZ,
        currency: String = "CZK",
        name: String = NAME,
        executed: String? = null,
    ) = ObjectMapper().writeValueAsString(
        mapOf(
            "id" to "$id", "partyId" to "$party", "status" to status, "mcpClientId" to "mcp-hosted-reference",
            "operationType" to type, "debtorAccountId" to "$account", "creditorIban" to iban,
            "creditorName" to name, "amount" to "1250.50", "currency" to currency, "reference" to null,
            "payloadSha256" to hash(type, iban, currency), "approvalRequestId" to "mcp-proposal:$id",
            "expiresAt" to expiresAt.toString(), "executedPaymentId" to executed,
        ),
    )

    private fun upstream(
        proposal: String = proposalJson(),
        scaOk: Boolean = true,
        storeApprove: Int = 200,
        paymentStatus: Int = 201,
    ): UpstreamClient = mockk<UpstreamClient>(relaxed = true).also { up ->
        every { up.get(match { it.contains("/payment-proposals/$id") }, any()) } returns Response.ok(proposal).build()
        every { up.get(match { it.contains("/accounts/$account") }, any()) } returns
            Response.ok("""{"id":"$account","partyId":"$party","accountNumber":"$CZ"}""").build()
        every { up.get(match { it.contains("/parties/") }, any()) } returns Response.ok("""{"legalName":"C"}""").build()
        every { up.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            if (scaOk) Response.ok("""{"status":"CONSUMED"}""").build() else Response.status(409).entity("{}").build()
        every { up.post(match { it.contains("-payments") }, any(), any(), any()) } returns
            Response.status(paymentStatus).entity("""{"id":"$PAYMENT","status":"RECEIVED"}""").build()
        every { up.post(match { it.endsWith("/approve") }, any(), any(), any()) } returns
            Response.status(storeApprove).entity("{}").build()
        every { up.post(match { it.endsWith("/execution") }, any(), any(), any()) } returns Response.ok("{}").build()
    }

    private fun body(h: String = hash()) = """{"payloadSha256":"$h"}"""

    private fun paymentPosts(up: UpstreamClient, n: Int) =
        verify(exactly = n) { up.post(match { it.contains("-payments") }, any(), any(), any()) }

    @Test
    fun `SCA is spent, then the store approves, then the payment is created and recorded`() {
        val up = upstream()
        val consume = slot<String>()
        every { up.post(match { it.contains("/sca/challenges/") }, any(), capture(consume)) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        val idem = slot<String>()
        every { up.post(match { it.contains("/domestic-payments") }, any(), any(), capture(idem)) } returns
            Response.status(201).entity("""{"id":"$PAYMENT","status":"RECEIVED"}""").build()

        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(201)

        verifyOrder {
            up.post(match { it.contains("/sca/challenges/") }, any(), any())
            up.post(
                "http://delegation/api/v1/payment-proposals/$id/approve",
                any(),
                any(),
                "mcp-proposal-approve-$id-$SCA",
            )
            up.post(match { it.contains("/domestic-payments") }, any(), any(), any())
            up.post("http://delegation/api/v1/payment-proposals/$id/execution", any(), any(), any())
        }
        assertThat(consume.captured).contains("\"approvalRequestId\":\"mcp-proposal:$id\"")
        assertThat(consume.captured).contains("\"payloadSha256\":\"${hash()}\"")
        assertThat(idem.captured).isEqualTo("mcp-proposal-$id")
        verify { up.get("http://delegation/api/v1/payment-proposals/$id?partyId=$party", party.toString()) }
    }

    @Test
    fun `a store outage on the read is 503, not proposal-not-found`() {
        val up = upstream()
        every { up.get(match { it.contains("/payment-proposals/$id") }, any()) } returns Response.status(502).build()
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(503)
        verify(exactly = 0) { up.post(match { it.contains("/sca/challenges/") }, any(), any()) }
        paymentPosts(up, 0)
    }

    @Test
    fun `a store outage on approve after SCA is 503 and sends no payment`() {
        val up = upstream(storeApprove = 503)
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(503)
        paymentPosts(up, 0)
    }

    @Test
    fun `a store refusal after SCA sends no payment`() {
        val up = upstream(storeApprove = 409)
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(409)
        paymentPosts(up, 0)
        verify(exactly = 0) { up.post(match { it.endsWith("/execution") }, any(), any(), any()) }
    }

    @Test
    fun `a refused SCA touches neither the store nor the payment`() {
        val up = upstream(scaOk = false)
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(403)
        verify(exactly = 0) { up.post(match { it.contains("/payment-proposals/") }, any(), any(), any()) }
        paymentPosts(up, 0)
    }

    @Test
    fun `an expired PENDING proposal is refused before SCA`() {
        val up = upstream(proposal = proposalJson(expiresAt = now))
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `a payment failure leaves it retryable and the retry pays once under the same key`() {
        val up = upstream(paymentStatus = 503)
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(503)
        verify(exactly = 0) { up.post(match { it.endsWith("/execution") }, any(), any(), any()) }

        val retry = upstream(proposal = proposalJson(status = "APPROVED"))
        val keys = mutableListOf<String>()
        every { retry.post(match { it.contains("/domestic-payments") }, any(), any(), capture(keys)) } returns
            Response.status(201).entity("""{"id":"$PAYMENT","status":"RECEIVED"}""").build()
        assertThat(resource(retry).approveMcpProposal(id, body(), SCA).status).isEqualTo(201)
        assertThat(keys).containsExactly("mcp-proposal-$id")
        verify(exactly = 1) { retry.post(match { it.endsWith("/execution") }, any(), any(), any()) }
    }

    @Test
    fun `a second approval after success is refused`() {
        val up = upstream(proposal = proposalJson(status = "EXECUTED", executed = PAYMENT))
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `another party's proposal is not found`() {
        val up = upstream()
        every { up.get(match { it.contains("/payment-proposals/$id") }, any()) } returns Response.status(404).build()
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(404)
    }

    @Test
    fun `malformed or missing hash and an empty body are 400`() {
        val up = upstream()
        assertThat(resource(up).approveMcpProposal(id, """{"payloadSha256":"xyz"}""", SCA).status).isEqualTo(400)
        assertThat(resource(up).approveMcpProposal(id, "", SCA).status).isEqualTo(400)
        assertThat(resource(up).approveMcpProposal(id, null, SCA).status).isEqualTo(400)
        verify(exactly = 0) { up.post(any(), any(), any()) }
        verify(exactly = 0) { up.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a mismatched hash is refused before SCA`() {
        val up = upstream()
        assertThat(resource(up).approveMcpProposal(id, body("b".repeat(64)), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `a spoofing payee name from the store is refused as malformed`() {
        val up = upstream(proposal = proposalJson(name = "Pay‮eman"))
        assertThat(resource(up).approveMcpProposal(id, body(), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `the SEPA path follows the same order`() {
        val up = upstream(proposal = proposalJson(type = "SEPA_PAYMENT", iban = DE, currency = "EUR"))
        every { up.get(match { it.contains("/accounts/$account") }, any()) } returns
            Response.ok("""{"id":"$account","partyId":"$party","accountNumber":"$CZ"}""").build()
        val r = resource(up).approveMcpProposal(id, body(hash("SEPA_PAYMENT", DE, "EUR")), SCA)
        assertThat(r.status).isEqualTo(201)
        verifyOrder {
            up.post(match { it.contains("/sca/challenges/") }, any(), any())
            up.post(
                "http://delegation/api/v1/payment-proposals/$id/approve",
                any(),
                any(),
                "mcp-proposal-approve-$id-$SCA",
            )
            up.post(match { it.contains("/sepa-payments") }, any(), any(), any())
        }
    }

    @Test
    fun `an app-client token without the edge audience cannot reach the approval`() {
        val up = upstream()
        val r = resource(up, aud = setOf("openbank-mcp"))
        assertThat(r.approveMcpProposal(id, body(), SCA).status).isEqualTo(403)
        assertThat(r.listMcpProposals().status).isEqualTo(403)
        verify(exactly = 0) { up.get(match { it.contains("/payment-proposals") }, any()) }
    }

    @Test
    fun `a payment the store did not record is audited as its own outcome, even when recording throws`() {
        val up = upstream()
        every { up.post(match { it.endsWith("/execution") }, any(), any(), any()) } throws
            RuntimeException("store down")
        org.assertj.core.api.Assertions.assertThatThrownBy {
            resource(up).approveMcpProposal(id, body(), SCA)
        }.isInstanceOf(RuntimeException::class.java)
        verify(exactly = 1) {
            audit.emit(
                eventType = "CUSTOMER_MCP_PROPOSAL_EXECUTION_UNRECORDED",
                partyId = party.toString(),
                operation = "mcp-proposals.approve",
                result = "FAILURE",
                resourceId = id.toString(),
                details = any(),
            )
        }
    }

    @Test
    fun `an MCP client token or a delegated token cannot reach the approval`() {
        for (r in listOf(resource(upstream(), azp = "mcp-hosted-reference"), resource(upstream(), act = true))) {
            assertThat(r.approveMcpProposal(id, body(), SCA).status).isEqualTo(403)
            assertThat(r.listMcpProposals().status).isEqualTo(403)
            assertThat(r.rejectMcpProposal(id).status).isEqualTo(403)
        }
    }

    private companion object {
        const val CZ = "CZ6508000000192000145399"
        const val DE = "DE89370400440532013000"
        const val NAME = "Elektrárna Praha a.s."
        const val SCA = "7b1d2c3e-4f50-4a6b-8c7d-9e0f1a2b3c4d"
        const val PAYMENT = "9a8b7c6d-5e4f-4321-8765-0fedcba98765"
    }
}
