// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-unit coverage of the MCP JSON-RPC dispatch + the deny-by-default OPA gate (ADR-0181). No
 * Quarkus/Vert.x context — the endpoint is a POJO with injected collaborators, so the protocol and
 * the authorization decision are provable without a running server or a real PDP.
 */
class McpEndpointTest {

    private val mapper = jacksonObjectMapper()

    private fun endpoint(pdp: PolicyDecisionPoint): McpEndpoint {
        val stub = StubReads(mapper)
        val registry = McpToolRegistry(stub, stub, mapper)
        return McpEndpoint(registry, pdp, mapper, "openbank-mcp", "0.1.0", "2025-06-18")
    }

    private fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.valueToTree(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private fun body(resp: Any?): JsonNode = mapper.valueToTree(resp)

    @Test
    fun `initialize returns protocol + server info`() {
        val resp = body(endpoint(allowAll()).handle(rpc("initialize")).entity)
        assertThat(resp.path("result").path("serverInfo").path("name").asText()).isEqualTo("openbank-mcp")
        assertThat(resp.path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18")
    }

    @Test
    fun `tools list exposes the curated tool set`() {
        val resp = body(endpoint(allowAll()).handle(rpc("tools/list")).entity)
        val names = resp.path("result").path("tools").map { it.path("name").asText() }
        assertThat(
            names,
        ).contains("list_accounts", "get_balance", "list_transactions", "list_consents", "propose_payment")
    }

    @Test
    fun `tools call is allowed when the PDP permits`() {
        val resp = body(
            endpoint(allowAll()).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("isError").asBoolean(false)).isFalse()
    }

    @Test
    fun `tools call is denied when the PDP refuses`() {
        val resp = body(
            endpoint(denyAll()).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("isError").asBoolean()).isTrue()
        assertThat(resp.path("result").path("content").first().path("text").asText()).contains("Denied by policy")
    }

    @Test
    fun `deny-by-default - a tool with no capability is refused before the PDP`() {
        // exploding PDP proves the refusal happens WITHOUT ever consulting policy.
        val resp = body(
            endpoint(exploding()).handle(
                rpc("tools/call", mapOf("name" to "delete_everything", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("isError").asBoolean()).isTrue()
        assertThat(resp.path("result").path("content").first().path("text").asText()).contains("not permitted")
    }

    @Test
    fun `pdp outage fails closed`() {
        val resp = body(
            endpoint(exploding()).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("isError").asBoolean()).isTrue()
        assertThat(
            resp.path("result").path("content").first().path("text").asText(),
        ).contains("Authorization unavailable")
    }

    private fun allowAll() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true)
    }

    private fun denyAll() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = false, reason = "no matching allow rule")
    }

    private fun exploding() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision = error("PDP down")
    }

    private class StubReads(private val m: com.fasterxml.jackson.databind.ObjectMapper) :
        AccountReadPort,
        ProposalPort {
        override fun listAccounts(consentContext: ConsentContext): JsonNode = m.createObjectNode().put("ok", true)
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode =
            m.createObjectNode().put("ok", true)
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
            m.createObjectNode().put("ok", true)
        override fun listConsents(consentContext: ConsentContext): JsonNode = m.createObjectNode().put("ok", true)
        override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode =
            m.createObjectNode().put("status", "PROPOSED")
    }
}
