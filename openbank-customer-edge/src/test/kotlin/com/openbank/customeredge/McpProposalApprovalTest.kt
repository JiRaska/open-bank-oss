// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.CustomerMcpProposal
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * ADR-0383 D6 at the edge: a proposal is approved only by the customer's own app session, only
 * with an SCA challenge linked to its payload hash, executes through the ordinary domestic path,
 * and nothing is recorded in the store unless that challenge was actually spent.
 */
class McpProposalApprovalTest {
    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val hash = CustomerMcpProposal.canonicalSha256(
        id, party, "DOMESTIC_PAYMENT", account, IBAN, "Elektrárna Praha a.s.", "1250.50", "CZK", null,
    )

    private fun resource(up: UpstreamClient, azp: String = "openbank-app", act: Boolean = false) = CustomerEdgeResource(
        up,
        mockk(relaxed = true),
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        jwt = mockk(relaxed = true) {
            every { getClaim<String>("party_id") } returns party.toString()
            every { subject } returns party.toString()
            every { getClaim<Any?>("azp") } returns azp
            every { containsClaim("act") } returns act
        }
        objectMapper = ObjectMapper()
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        accountServiceUrl = "http://account"
        domesticPaymentServiceUrl = "http://dompay"
        partyServiceUrl = "http://party"
        scaServiceUrl = "http://sca"
        delegationServiceUrl = "http://delegation"
    }

    private fun proposalJson(status: String = "PENDING") = """
        {"id":"$id","partyId":"$party","status":"$status","mcpClientId":"mcp-hosted-reference",
         "operationType":"DOMESTIC_PAYMENT","debtorAccountId":"$account","creditorIban":"$IBAN",
         "creditorName":"Elektrárna Praha a.s.","amount":"1250.50","currency":"CZK","reference":null,
         "payloadSha256":"$hash","approvalRequestId":"mcp-proposal:$id"}
    """.trimIndent().replace("\n", "")

    private fun upstream(scaOk: Boolean = true): UpstreamClient = mockk<UpstreamClient>(relaxed = true).also { up ->
        every { up.get(match { it.contains("/payment-proposals/$id") }, any()) } returns
            Response.ok(proposalJson()).build()
        every { up.get(match { it.contains("/accounts/$account") }, any()) } returns
            Response.ok("""{"id":"$account","partyId":"$party","accountNumber":"CZ6508000000192000145399"}""").build()
        every { up.get(match { it.contains("/parties/") }, any()) } returns
            Response.ok("""{"legalName":"Customer Name"}""").build()
        every { up.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            if (scaOk) Response.ok("""{"status":"CONSUMED"}""").build() else Response.status(409).entity("{}").build()
        every { up.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"$PAYMENT","status":"RECEIVED"}""").build()
        every { up.post(match { it.contains("/payment-proposals/") }, any(), any()) } returns
            Response.ok("{}").build()
    }

    private fun approveBody(h: String = hash) = """{"payloadSha256":"$h"}"""

    @Test
    fun `approval spends a challenge linked to the proposal hash and records approval then execution`() {
        val up = upstream()
        val consume = slot<String>()
        every { up.post(match { it.contains("/sca/challenges/") }, any(), capture(consume)) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        val idem = slot<String>()
        every { up.post(match { it.contains("/domestic-payments") }, any(), any(), capture(idem)) } returns
            Response.status(201).entity("""{"id":"$PAYMENT","status":"RECEIVED"}""").build()

        val resp = resource(up).approveMcpProposal(id, approveBody(), SCA)

        assertThat(resp.status).isEqualTo(201)
        assertThat(consume.captured).contains("\"approvalRequestId\":\"mcp-proposal:$id\"")
        assertThat(consume.captured).contains("\"payloadSha256\":\"$hash\"")
        assertThat(consume.captured).contains("\"amount\":\"1250.50\"")
        assertThat(idem.captured).isEqualTo("mcp-proposal-$id")
        verify(exactly = 1) { up.post("http://delegation/api/v1/payment-proposals/$id/approve", any(), any()) }
        verify(exactly = 1) { up.post("http://delegation/api/v1/payment-proposals/$id/execution", any(), any()) }
    }

    @Test
    fun `a refused SCA records nothing in the store`() {
        val up = upstream(scaOk = false)
        val resp = resource(up).approveMcpProposal(id, approveBody(), SCA)
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { up.post(match { it.contains("/payment-proposals/") }, any(), any()) }
        verify(exactly = 0) { up.post(match { it.contains("/domestic-payments") }, any(), any(), any()) }
    }

    @Test
    fun `a missing challenge records nothing`() {
        val up = upstream()
        assertThat(resource(up).approveMcpProposal(id, approveBody(), null).status).isEqualTo(403)
        verify(exactly = 0) { up.post(match { it.contains("/payment-proposals/") }, any(), any()) }
    }

    @Test
    fun `a mismatched hash is refused before any SCA is spent`() {
        val up = upstream()
        assertThat(resource(up).approveMcpProposal(id, approveBody("b".repeat(64)), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    @Test
    fun `an MCP client token or a delegated token cannot reach the approval`() {
        for (r in listOf(resource(upstream(), azp = "mcp-hosted-reference"), resource(upstream(), act = true))) {
            assertThat(r.approveMcpProposal(id, approveBody(), SCA).status).isEqualTo(403)
            assertThat(r.listMcpProposals().status).isEqualTo(403)
            assertThat(r.rejectMcpProposal(id).status).isEqualTo(403)
        }
    }

    @Test
    fun `a proposal no longer pending is refused`() {
        val up = upstream()
        every { up.get(match { it.contains("/payment-proposals/$id") }, any()) } returns
            Response.ok(proposalJson("APPROVED")).build()
        assertThat(resource(up).approveMcpProposal(id, approveBody(), SCA).status).isEqualTo(409)
        verify(exactly = 0) { up.post(match { it.contains("/sca/") }, any(), any()) }
    }

    private companion object {
        const val IBAN = "CZ6508000000192000145399"
        const val SCA = "7b1d2c3e-4f50-4a6b-8c7d-9e0f1a2b3c4d"
        const val PAYMENT = "9a8b7c6d-5e4f-4321-8765-0fedcba98765"
    }
}
