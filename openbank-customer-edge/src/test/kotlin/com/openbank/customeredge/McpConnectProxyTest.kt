// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.CustomerMcpConnectAccountScope
import com.openbank.customeredge.infrastructure.rest.CustomerMcpConnectRequests
import com.openbank.customeredge.infrastructure.rest.CustomerMcpConnectReview
import com.openbank.customeredge.infrastructure.rest.CustomerMcpConnectSelection
import com.openbank.customeredge.infrastructure.rest.CustomerMcpConnections
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import com.openbank.libs.testing.openapi.OpenApiWireValidator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class McpConnectProxyTest {
    private val caller = UUID.randomUUID()
    private val mergedParty = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val requestRef = "e".repeat(64)
    private val requestsUrl = "http://delegation/api/v1/delegations/mcp-connect/customer/requests"
    private val reviewUrl = "$requestsUrl/$requestRef"
    private val connectionId = UUID.randomUUID()
    private val connectionsUrl = "http://delegation/api/v1/delegations/mcp-connect/customer/connections"
    private val mapper = jacksonObjectMapper()

    private fun edge(
        upstream: UpstreamClient,
        azp: String = "openbank-app",
        act: Boolean = false,
        tokenAudience: Set<String> = setOf("openbank-edge"),
        enabled: Boolean = true,
    ) = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns caller.toString()
            every { subject } returns caller.toString()
            every { getClaim<Any?>("azp") } returns azp
            every { containsClaim("act") } returns act
            every { audience } returns tokenAudience
        }
        objectMapper = mapper
        partyMergeResolver = mockk { every { resolve(caller) } returns mergedParty }
        delegationServiceUrl = "http://delegation"
        configureMcpConnect(enabled)
    }

    private fun reviewJson(party: UUID = mergedParty) = """
        {
          "requestRef":"$requestRef","partyId":"$party","clientId":"synthetic-assistant","redirectHost":"localhost",
          "resource":"https://mcp.test/customer/mcp","scopes":["openid"],"parJkt":"test-public-thumbprint",
          "expiresAtEpochSecond":1800000000,"status":"SELECTED","accounts":[],"ownerOnly":"never forwarded"
        }
    """.trimIndent()

    @Test
    fun `list and detail scope owner calls to the canonical app party and redact unexpected upstream fields`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(requestsUrl, mergedParty.toString()) } returns
            Response.ok("""{"requests":[${reviewJson()}],"unexpected":"private"}""").build()
        every { upstream.get(reviewUrl, mergedParty.toString()) } returns Response.ok(reviewJson()).build()

        val edge = edge(upstream)
        val list = edge.listMcpConnectRequests()
        val detail = edge.getMcpConnectRequest(requestRef)
        assertThat(list.status).isEqualTo(200)
        assertThat(list.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        val request = (list.entity as CustomerMcpConnectRequests).requests.single()
        assertThat(request.requestRef).isEqualTo(requestRef)
        assertThat(request.partyId).isEqualTo(mergedParty)
        assertThat(detail.entity).isEqualTo(request)
        val json = mapper.writeValueAsString(list.entity)
        assertThat(json).doesNotContain("ownerOnly", "unexpected", "challengeId")
        val contract = OpenApiWireValidator()
        assertThat(contract.violations(json, "/mcp/connect-requests")).isEmpty()
        assertThat(contract.violations(mapper.writeValueAsString(detail.entity), "/mcp/connect-requests/{requestRef}"))
            .isEmpty()
    }

    @Test
    fun `selection forwards typed accounts on PUT using only the authenticated party`() {
        val upstream = mockk<UpstreamClient>()
        val selection = CustomerMcpConnectSelection(
            listOf(CustomerMcpConnectAccountScope(account, listOf("ACCOUNT_READ_BALANCES"))),
        )
        every {
            upstream.put("$reviewUrl/selection", mergedParty.toString(), mapper.writeValueAsString(selection))
        } returns Response.ok(reviewJson()).build()
        val edge = edge(upstream)
        val reply = edge.selectMcpConnectAccounts(requestRef, selection)
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.entity).isInstanceOf(CustomerMcpConnectReview::class.java)
        verify(exactly = 1) { upstream.put(any(), any(), any()) }
        every { upstream.put("$reviewUrl/selection", mergedParty.toString(), any()) } returns
            Response.status(403).entity("""{"internalReason":"never forwarded"}""").build()
        val denied = edge.selectMcpConnectAccounts(requestRef, selection)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.entity.toString()).doesNotContain("internalReason")
    }

    @Test
    fun `null account and capability elements return 400 without forwarding`() {
        val upstream = mockk<UpstreamClient>()
        val edge = edge(upstream)
        val inputs = mapOf(
            """{"accounts":[null]}""" to "accounts[0]",
            """{"accounts":[{"accountId":"$account","capabilities":[null]}]}""" to "accounts[0].capabilities[0]",
        )

        inputs.forEach { (payload, expectedIndex) ->
            val selection = mapper.readValue(payload, CustomerMcpConnectSelection::class.java)
            val reply = edge.selectMcpConnectAccounts(requestRef, selection)
            assertThat(reply.status).isEqualTo(400)
            assertThat(reply.entity.toString()).contains(expectedIndex)
            assertThat(reply.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        }
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
    }

    @Test
    fun `disabled wrong identity and malformed references do not reach the owner`() {
        val upstream = mockk<UpstreamClient>()
        assertThat(edge(upstream, enabled = false).listMcpConnectRequests().status).isEqualTo(404)
        assertThat(edge(upstream, azp = "synthetic-assistant").listMcpConnectRequests().status).isEqualTo(403)
        assertThat(edge(upstream, act = true).listMcpConnectRequests().status).isEqualTo(403)
        assertThat(edge(upstream, tokenAudience = setOf("mcp-service")).listMcpConnectRequests().status).isEqualTo(403)
        val businessContext = edge(upstream).apply {
            requestHeaders = mockk {
                every { getHeaderString(CustomerEdgeResource.ACTING_FOR_HEADER) } returns account.toString()
            }
        }
        assertThat(businessContext.listMcpConnectRequests().status).isEqualTo(403)
        assertThat(edge(upstream).getMcpConnectRequest("invalid/ref").status).isEqualTo(400)
        assertThat(edge(upstream).selectMcpConnectAccounts(requestRef, null).status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
    }

    @Test
    fun `connections list is scoped to the same party and matches the public contract`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(connectionsUrl, mergedParty.toString()) } returns
            Response.ok(connectionListJson(mergedParty)).build()
        val edge = edge(upstream)
        val listed = edge.listMcpConnections()
        assertThat(listed.status).isEqualTo(200)
        assertThat(listed.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        val body = listed.entity as CustomerMcpConnections
        assertThat(body.connections.single().connectionId).isEqualTo(connectionId)
        val json = mapper.writeValueAsString(body)
        assertThat(json).doesNotContain("ownerOnly")
        assertThat(OpenApiWireValidator().violations(json, "/mcp/connections")).isEmpty()
        every { upstream.get(connectionsUrl, mergedParty.toString()) } returns
            Response.ok(connectionListJson(caller)).build()
        assertThat(edge.listMcpConnections().status).isEqualTo(503)
    }

    @Test
    fun `revoke passes verified party only and requires the owner's no content confirmation`() {
        val upstream = mockk<UpstreamClient>()
        val url = "$connectionsUrl/$connectionId/revoke"
        every { upstream.post(url, mergedParty.toString(), "", idempotencyKey = connectionId.toString()) } returnsMany
            listOf(
                Response.noContent().build(),
                Response.ok("""{"unexpected":true}""").build(),
                Response.status(404).build(),
            )
        val edge = edge(upstream)
        val revoked = edge.revokeMcpConnection(connectionId)
        assertThat(revoked.status).isEqualTo(204)
        assertThat(revoked.hasEntity()).isFalse()
        assertThat(revoked.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        assertThat(edge.revokeMcpConnection(connectionId).status).isEqualTo(503)
        assertThat(edge.revokeMcpConnection(connectionId).status).isEqualTo(404)
        assertThat(edge(upstream, act = true).revokeMcpConnection(connectionId).status).isEqualTo(403)
        assertThat(edge(upstream, enabled = false).revokeMcpConnection(connectionId).status)
            .isEqualTo(404)
        verify(exactly = 3) { upstream.post(url, mergedParty.toString(), "", idempotencyKey = connectionId.toString()) }
    }

    private fun connectionListJson(party: UUID) = """
        {
          "connections": [{
            "connectionId":"$connectionId","partyId":"$party","clientId":"synthetic-assistant",
            "resource":"https://mcp.test/customer/mcp","expiresAtEpochSecond":1800000000,
            "status":"ACTIVE","accounts":[],"ownerOnly":"never forwarded"
          }]
        }
    """.trimIndent()

    @Test
    fun `owner not found remains not found and invalid owner data fails as unavailable`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(reviewUrl, mergedParty.toString()) } returns Response.status(404).build()
        every { upstream.get(requestsUrl, mergedParty.toString()) } returns Response.ok("{}").build()
        assertThat(edge(upstream).getMcpConnectRequest(requestRef).status).isEqualTo(404)
        assertThat(edge(upstream).listMcpConnectRequests().status).isEqualTo(503)
        every { upstream.get(reviewUrl, mergedParty.toString()) } returns Response.ok(reviewJson(caller)).build()
        assertThat(edge(upstream).getMcpConnectRequest(requestRef).status).isEqualTo(503)
        every { upstream.get(requestsUrl, mergedParty.toString()) } returns
            Response.ok("""{"requests":[${reviewJson(caller)}]}""").build()
        assertThat(edge(upstream).listMcpConnectRequests().status).isEqualTo(503)
    }
}
