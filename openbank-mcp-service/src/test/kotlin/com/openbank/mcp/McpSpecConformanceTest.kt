// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpPiiMasker
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.PolicyFilteredToolCatalog
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wire-protocol conformance for the MCP surface (issue #1922, the "no MCP spec conformance run"
 * half of its remaining scope).
 *
 * **Why this exists rather than a vendored external suite.** The MCP conformance suites are Node
 * clients that speak stdio or SSE to a running server; wiring one in would add a runtime, a
 * network dependency and a container to a gate whose job is to answer a question that is decidable
 * from the endpoint alone. What is actually checkable here is the part this server *implements* —
 * the JSON-RPC 2.0 envelope rules MCP inherits verbatim, and the handful of MCP-specific shape
 * rules for `initialize` / `tools/list` / `tools/call`. Those are asserted below, against the real
 * dispatcher.
 *
 * **The one distinction that matters most** is the last group: a TOOL failure is a successful
 * JSON-RPC *result* carrying `isError: true`, whereas a PROTOCOL failure is a JSON-RPC *error*
 * object. Conflating them is the classic MCP server bug — it makes a policy denial look to the
 * client like the server is broken (and, worse, makes a broken server look like a denial). This
 * server gets it right, and now something says so.
 *
 * Deliberately NOT asserted: `initialize` negotiating a client-proposed protocolVersion. This
 * server pins one version by config and does not negotiate; pretending otherwise in a test would
 * be asserting a behaviour that does not exist.
 */
class McpSpecConformanceTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val meters = SimpleMeterRegistry()
    private val limiter = McpRateLimiter()

    private fun endpoint(): McpEndpoint {
        val stub = ConformanceReads(mapper)
        val toolRegistry = McpToolRegistry(stub, stub, StubMarketingReachPort(mapper), McpPiiMasker(mapper), mapper)
        return McpEndpoint(
            registry = toolRegistry,
            pdp = AllowAll,
            auditor = McpCallAuditor(SinkPublisher),
            callerResolver = CallerContextResolver(
                TestJsonWebToken(
                    mapOf("sub" to "agent:conformance", "consent_id" to "6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11"),
                ),
            ),
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            metrics = McpMetricsAdapter(meters)
            rateLimiter = limiter
            toolsCatalog = PolicyFilteredToolCatalog(toolRegistry, AllowAll, 0)
        }
    }

    private fun send(request: Map<String, Any?>): Response = endpoint().handle(mapper.valueToTree(request))

    private fun bodyOf(response: Response): JsonNode = mapper.valueToTree(response.entity)

    private fun request(method: String, id: Any? = 1, params: Any? = emptyMap<String, Any>()) =
        mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)

    // -----------------------------------------------------------------------------------
    // JSON-RPC 2.0 envelope — inherited verbatim by MCP.
    // -----------------------------------------------------------------------------------

    @Test
    fun `every response carries the jsonrpc 2 0 version marker`() {
        listOf("initialize", "ping", "tools/list", "no/such/method").forEach { method ->
            val body = bodyOf(send(request(method)))
            assertThat(body.path("jsonrpc").asText())
                .`as`("response to '%s' is missing the jsonrpc version marker: %s", method, body)
                .isEqualTo("2.0")
        }
    }

    @Test
    fun `the response id echoes the request id, preserving its JSON type`() {
        // A client correlates by exact id. Coercing a string id to a number (or vice versa) breaks
        // correlation silently — the client waits forever for a response it already received.
        val numeric = bodyOf(send(request("ping", id = 42)))
        assertThat(numeric.path("id").isNumber).`as`("numeric id lost its type: %s", numeric).isTrue()
        assertThat(numeric.path("id").asInt()).isEqualTo(42)

        val textual = bodyOf(send(request("ping", id = "req-abc")))
        assertThat(textual.path("id").isTextual).`as`("string id lost its type: %s", textual).isTrue()
        assertThat(textual.path("id").asText()).isEqualTo("req-abc")
    }

    @Test
    fun `an error response carries a well-formed error object and no result member`() {
        val body = bodyOf(send(request("no/such/method")))

        assertThat(body.path("error").path("code").isInt)
            .`as`("error.code must be an integer: %s", body).isTrue()
        assertThat(body.path("error").path("code").asInt())
            .`as`("unknown method must be METHOD_NOT_FOUND: %s", body).isEqualTo(-32601)
        assertThat(body.path("error").path("message").isTextual)
            .`as`("error.message must be a string: %s", body).isTrue()
        // "result and error MUST NOT both be included." A response carrying both is ambiguous, and
        // a lenient client picks whichever it looks at first.
        assertThat(body.has("result")).`as`("an error response also carried a result: %s", body).isFalse()
    }

    @Test
    fun `a successful response carries no error member`() {
        val body = bodyOf(send(request("ping")))
        assertThat(body.has("error")).`as`("a success response also carried an error: %s", body).isFalse()
    }

    @Test
    fun `a notification produces no response body`() {
        // JSON-RPC 2.0: a request without an id is a notification and MUST NOT be answered.
        // MCP's client sends `notifications/initialized` immediately after the handshake; answering
        // it leaves an uncorrelatable response the client must discard, and a strict client errors.
        val response = send(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"))
        assertThat(response.status).isEqualTo(Response.Status.NO_CONTENT.statusCode)
        assertThat(response.entity).`as`("a notification was answered with a body").isNull()
    }

    // -----------------------------------------------------------------------------------
    // MCP-specific result shapes.
    // -----------------------------------------------------------------------------------

    @Test
    fun `initialize returns the three members a client needs to proceed`() {
        val result = bodyOf(send(request("initialize"))).path("result")

        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18")
        assertThat(result.path("serverInfo").path("name").asText()).isNotEmpty()
        assertThat(result.path("serverInfo").path("version").asText()).isNotEmpty()
        assertThat(result.path("capabilities").isObject)
            .`as`("capabilities must be an object even when empty: %s", result).isTrue()
        // A server that advertises a `tools` capability must actually serve tools/list, and vice
        // versa — a client uses this to decide whether to call it at all.
        assertThat(result.path("capabilities").has("tools"))
            .`as`("this server serves tools/list but does not advertise the tools capability: %s", result)
            .isTrue()
    }

    @Test
    fun `every advertised tool carries a name, a description and an object input schema`() {
        val tools = bodyOf(send(request("tools/list"))).path("result").path("tools")

        assertThat(tools.isArray).`as`("tools must be an array: %s", tools).isTrue()
        assertThat(tools).`as`("tools/list returned nothing — the assertions below would be vacuous").isNotEmpty
        tools.forEach { tool ->
            val name = tool.path("name").asText()
            assertThat(name).`as`("a tool has no name: %s", tool).isNotEmpty()
            assertThat(tool.path("description").asText())
                .`as`("tool '%s' has no description — the model selects on this", name).isNotEmpty()
            assertThat(tool.path("inputSchema").path("type").asText())
                .`as`("tool '%s' inputSchema.type must be \"object\": %s", name, tool).isEqualTo("object")
            assertThat(tool.path("inputSchema").has("properties"))
                .`as`("tool '%s' inputSchema has no properties member: %s", name, tool).isTrue()
        }
    }

    @Test
    fun `tool names are unique, since a client keys its tool table by name`() {
        val names = bodyOf(send(request("tools/list"))).path("result").path("tools").map { it.path("name").asText() }
        assertThat(names).doesNotHaveDuplicates()
    }

    @Test
    fun `a tool result is content-shaped - a list of typed content items`() {
        val result = bodyOf(
            send(
                request(
                    "tools/call",
                    params = mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>()),
                ),
            ),
        ).path("result")

        assertThat(result.path("content").isArray).`as`("result.content must be an array: %s", result).isTrue()
        assertThat(result.path("content")).isNotEmpty
        result.path("content").forEach { item ->
            assertThat(item.path("type").asText())
                .`as`("a content item has no type discriminator: %s", item).isNotEmpty()
            assertThat(item.path("text").isTextual)
                .`as`("a text content item has no text member: %s", item).isTrue()
        }
    }

    // -----------------------------------------------------------------------------------
    // The distinction that matters: tool failure != protocol failure.
    // -----------------------------------------------------------------------------------

    @Test
    fun `an unknown TOOL is a result with isError, never a JSON-RPC error`() {
        val body = bodyOf(
            send(
                request("tools/call", params = mapOf("name" to "no_such_tool", "arguments" to emptyMap<String, Any>())),
            ),
        )

        assertThat(body.has("error"))
            .`as`(
                "a tool-level failure was reported as a PROTOCOL error, so the client cannot tell it apart " +
                    "from the server being broken: %s",
                body,
            )
            .isFalse()
        assertThat(body.path("result").path("isError").asBoolean())
            .`as`("an unknown tool must set result.isError: %s", body).isTrue()
        assertThat(body.path("result").path("content").first().path("text").asText()).isNotEmpty()
    }

    @Test
    fun `an unknown METHOD is a JSON-RPC error, never a result with isError`() {
        // The mirror of the test above. Both directions must hold, or the two failure classes are
        // still conflated — just in the other direction.
        val body = bodyOf(send(request("no/such/method")))

        assertThat(body.has("result"))
            .`as`("a protocol-level failure was reported as a tool result: %s", body).isFalse()
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32601)
    }

    // -----------------------------------------------------------------------------------

    private object AllowAll : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true, reason = "test")
    }

    private object SinkPublisher : AuditEventPublisher {
        override suspend fun publish(event: AuditEvent) = Unit
    }

    private class ConformanceReads(private val m: ObjectMapper) :
        AccountReadPort,
        ProposalPort {
        private fun payload() = m.createObjectNode().put("status", "ACTIVE").put("currency", "CZK")
        override fun listAccounts(consentContext: ConsentContext): JsonNode = payload()
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode = payload()
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
            payload()
        override fun listConsents(consentContext: ConsentContext): JsonNode = payload()
        override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode =
            m.createObjectNode().put("status", "PROPOSED")
    }
}
