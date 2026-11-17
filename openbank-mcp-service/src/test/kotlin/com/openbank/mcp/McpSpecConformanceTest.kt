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
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
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

    private val agentToken = TestJsonWebToken(
        mapOf("sub" to "agent:conformance", "consent_id" to "6a1f5f38-59a5-4e77-9f0d-2b1a7a2f1c11"),
    )

    private fun endpoint(
        token: TestJsonWebToken = agentToken,
        stub: ConformanceReads = ConformanceReads(mapper),
    ): McpEndpoint {
        val toolRegistry =
            McpToolRegistry(stub, stub, stub, stub, StubMarketingReachPort(mapper), McpPiiMasker(mapper), mapper)
        return McpEndpoint(
            registry = toolRegistry,
            pdp = AllowAll,
            auditor = McpCallAuditor(SinkPublisher),
            callerResolver = CallerContextResolver(token),
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
    // Issue #16617 layer 1 — extended conformance, written against what the server implements
    // today. Every @Disabled test below is a KNOWN DEVIATION from the MCP specification (revision
    // 2026-07-28, https://modelcontextprotocol.io/specification/2026-07-28), kept as an executable
    // expectation instead of a comment: it is a follow-up, not a pass. Production code is
    // deliberately untouched by this change. Remove @Disabled when the deviation is fixed.
    //
    // Context for the whole group: this server implements the initialize-handshake protocol shape
    // (pinned 2025-06-18). Revision 2026-07-28 is stateless: no initialize/session, the protocol
    // version and client capabilities travel in every request's `_meta`, and the HTTP binding
    // mandates MCP-Protocol-Version / Mcp-Method headers. That migration is itself a follow-up.
    // -----------------------------------------------------------------------------------

    private fun raw(json: String): JsonNode = bodyOf(endpoint().handle(mapper.readTree(json)))

    private fun assertJsonRpcRejection(body: JsonNode, what: String) {
        assertThat(body.has("result")).`as`("%s must not produce a result: %s", what, body).isFalse()
        assertThat(body.path("error").path("code").isInt).`as`("%s needs an error.code: %s", what, body).isTrue()
        assertThat(body.path("error").path("message").isTextual).isTrue()
    }

    @Test
    fun `request ids of every legal JSON type round-trip exactly`() {
        // JSON-RPC 2.0 ids are strings or integers. Correlation is by exact value, so a lossy
        // round trip (long truncated to int, unicode normalised, empty string dropped) strands the
        // client's pending request.
        val ids: List<Any> = listOf(0, -1, Long.MAX_VALUE, Long.MIN_VALUE, "", " ", "a/b", "é中😀")
        ids.forEach { id ->
            val sent = mapper.valueToTree<JsonNode>(id)
            val echoed = bodyOf(send(request("ping", id = id))).path("id")
            assertThat(echoed).`as`("id %s did not round-trip", sent).isEqualTo(sent)
        }
    }

    @Test
    fun `a non-object body is rejected with a JSON-RPC error and never throws`() {
        // A 500 here would be a bare stack-trace path on the first byte a client sends. Batches
        // (arrays) are not part of MCP since 2025-06-18; scalars were never valid requests.
        listOf("[]", "[1]", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]", "\"x\"", "42", "true", "null")
            .forEach { body -> assertJsonRpcRejection(raw(body), "body $body") }
    }

    @Test
    fun `a request without a method string is rejected rather than executed`() {
        listOf("{}", """{"jsonrpc":"2.0","id":1}""", """{"jsonrpc":"2.0","id":1,"method":7}""")
            .forEach { body -> assertJsonRpcRejection(raw(body), "body $body") }
    }

    @Test
    fun `initialize echoes a client version the server supports`() {
        val result = bodyOf(
            send(request("initialize", params = mapOf("protocolVersion" to "2025-06-18"))),
        ).path("result")
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18")
    }

    @Test
    fun `initialize answers an unsupported client version with a version the server does support`() {
        // Negotiation rule: when the requested version is unsupported the server responds with
        // another version it supports (never echoes the unsupported one, never errors the handshake).
        listOf("1999-01-01", "2026-07-28", "", "not-a-version").forEach { proposed ->
            val result = bodyOf(
                send(request("initialize", params = mapOf("protocolVersion" to proposed))),
            ).path("result")
            assertThat(result.path("protocolVersion").asText())
                .`as`("client proposed '%s'", proposed).isEqualTo("2025-06-18")
        }
    }

    @Test
    fun `initialize tolerates absent and malformed params`() {
        listOf(null, emptyMap<String, Any>(), "x", listOf(1), 7).forEach { params ->
            val body = bodyOf(send(request("initialize", params = params)))
            assertThat(body.path("result").path("protocolVersion").asText()).`as`("params=%s", params).isNotEmpty()
        }
    }

    @Test
    fun `tools list is deterministic and every inputSchema is structurally valid JSON Schema`() {
        val first = bodyOf(send(request("tools/list"))).path("result").path("tools")
        val second = bodyOf(send(request("tools/list"))).path("result").path("tools")
        assertThat(second.map { it.path("name").asText() }).isEqualTo(first.map { it.path("name").asText() })

        val validTypes = setOf("string", "integer", "number", "boolean", "object", "array", "null")
        first.forEach { tool ->
            val name = tool.path("name").asText()
            assertThat(name).`as`("tool name charset/length").matches("[A-Za-z0-9_.-]{1,128}")
            val schema = tool.path("inputSchema")
            assertThat(schema.path("properties").isObject).`as`("%s: properties must be an object", name).isTrue()
            val props = schema.path("properties").fieldNames().asSequence().toSet()
            schema.path("properties").fields().forEach { (prop, def) ->
                assertThat(validTypes).`as`("%s.%s type", name, prop).contains(def.path("type").asText())
            }
            val required = schema.path("required")
            if (schema.has("required")) {
                assertThat(required.isArray).`as`("%s: required must be an array", name).isTrue()
                assertThat(required.map { it.asText() }).`as`("%s: required names an undeclared property", name)
                    .isSubsetOf(props)
                assertThat(required.map { it.asText() }).doesNotHaveDuplicates()
            }
        }
    }

    @Test
    fun `an invalid tool argument never produces a success result`() {
        val body = raw(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{}}}""",
        )
        val rejected = body.has("error") || body.path("result").path("isError").asBoolean(false)
        assertThat(rejected).`as`("a call missing its required argument succeeded: %s", body).isTrue()
    }

    @Test
    fun `an unauthenticated caller gets no tools, no data and no downstream call`() {
        val reads = ConformanceReads(mapper)
        val anonymous = TestJsonWebToken(emptyMap())
        val ep = endpoint(token = anonymous, stub = reads)

        val list = bodyOf(ep.handle(mapper.valueToTree(request("tools/list")))).path("result").path("tools")
        assertThat(list).`as`("anonymous tools/list leaked the tool catalogue").isEmpty()

        TOOL_NAMES.forEach { tool ->
            val call = bodyOf(
                ep.handle(
                    mapper.valueToTree(
                        request("tools/call", params = mapOf("name" to tool, "arguments" to emptyMap<String, Any>())),
                    ),
                ),
            )
            assertThat(call.path("result").path("isError").asBoolean(false))
                .`as`("anonymous call to %s was not refused: %s", tool, call).isTrue()
            assertThat(call.toString()).doesNotContain("ACTIVE")
        }
        assertThat(reads.calls).`as`("an unauthenticated call reached a downstream port").isZero()
    }

    // ------------------------- KNOWN DEVIATIONS (disabled, see PR body) -------------------------

    @Disabled(
        "Deviation D1 (#16629): request without `id` that is not a notification is answered; the spec says ids are mandatory on requests and notifications get no response",
    )
    @Test
    fun `a request lacking an id is treated as a notification and is not answered`() {
        val response = send(mapOf("jsonrpc" to "2.0", "method" to "tools/list"))
        assertThat(response.entity).`as`("a notification-shaped tools/list was answered").isNull()
    }

    @Disabled(
        "Deviation D2 (#16629): an unknown notification (e.g. notifications/cancelled) is answered with -32601; notifications must never be answered",
    )
    @Test
    fun `an unknown notification is ignored, not answered with an error`() {
        val response = send(mapOf("jsonrpc" to "2.0", "method" to "notifications/cancelled"))
        assertThat(response.entity).isNull()
    }

    @Disabled("Deviation D3 (#16629): `id: null` is accepted and echoed; MCP forbids a null request id")
    @Test
    fun `a null request id is an Invalid Request`() {
        val body = bodyOf(send(request("ping", id = null)))
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32600)
    }

    @Disabled(
        "Deviation D4 (#16629): `jsonrpc` is not validated; a missing or non-2.0 marker must be -32600 Invalid Request",
    )
    @Test
    fun `a request without the jsonrpc 2 0 marker is an Invalid Request`() {
        listOf(
            """{"id":1,"method":"ping"}""",
            """{"jsonrpc":"1.0","id":1,"method":"ping"}""",
            """{"jsonrpc":2,"id":1,"method":"ping"}""",
        ).forEach { assertThat(raw(it).path("error").path("code").asInt()).`as`(it).isEqualTo(-32600) }
    }

    @Disabled(
        "Deviation D5 (#16629): non-object bodies and non-string methods answer -32601/-32602 shaped text, not -32600 Invalid Request",
    )
    @Test
    fun `structurally invalid requests use code -32600`() {
        listOf("[]", "[1]", "\"x\"", "42", "{}", """{"jsonrpc":"2.0","id":1,"method":7}""")
            .forEach { assertThat(raw(it).path("error").path("code").asInt()).`as`(it).isEqualTo(-32600) }
    }

    @Disabled("Deviation D6 (#16629): ping answers {\"pong\":true}; the spec result is an empty object")
    @Test
    fun `ping returns an empty result object`() {
        val result = bodyOf(send(request("ping"))).path("result")
        assertThat(result.isObject).isTrue()
        assertThat(result.size()).isZero()
    }

    @Disabled(
        "Deviation D7 (#16629): `resources` capability is advertised but resources/list is not implemented (-32601)",
    )
    @Test
    fun `every advertised capability is backed by an implemented method`() {
        val caps = bodyOf(send(request("initialize"))).path("result").path("capabilities")
        if (caps.has("resources")) {
            val listed = bodyOf(send(request("resources/list")))
            assertThat(listed.path("error").path("code").asInt(0))
                .`as`("resources capability advertised but resources/list says: %s", listed).isNotEqualTo(-32601)
        }
    }

    @Disabled(
        "Deviation D8 (#16629): an unknown tool is a result with isError (deny-by-default); the spec classifies it as a -32602 protocol error",
    )
    @Test
    fun `an unknown tool is a protocol error -32602`() {
        val body = raw(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}""",
        )
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602)
    }

    @Disabled(
        "Deviation D9 (#16629): tools/call without a string `name` falls through to the deny path; a request failing CallToolRequest is -32602",
    )
    @Test
    fun `a tools call without a string name is -32602`() {
        listOf("""{"arguments":{}}""", """{"name":7}""", """{"name":null}""", "null", """"x"""").forEach { params ->
            val body = raw("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":$params}""")
            assertThat(body.path("error").path("code").asInt()).`as`(params).isEqualTo(-32602)
        }
    }

    @Disabled(
        "Deviation D10 (#16629): argument validation failures are JSON-RPC -32602 errors; revision 2026-07-28 classes input validation as tool execution errors (isError) so the model can self-correct",
    )
    @Test
    fun `input validation failure is a tool execution error with isError`() {
        val body = raw(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_balance","arguments":{}}}""",
        )
        assertThat(body.has("error")).isFalse()
        assertThat(body.path("result").path("isError").asBoolean()).isTrue()
    }

    @Disabled(
        "Deviation D11 (#16629): no tool carries annotations; a read tool should declare readOnlyHint and propose_payment must not",
    )
    @Test
    fun `tools declare behavioural annotations`() {
        val tools = bodyOf(send(request("tools/list"))).path("result").path("tools").associateBy {
            it.path("name").asText()
        }
        listOf("list_accounts", "get_balance", "list_transactions", "list_consents", "get_statement")
            .forEach {
                assertThat(tools.getValue(it).path("annotations").path("readOnlyHint").asBoolean()).`as`(it).isTrue()
            }
        assertThat(tools.getValue("propose_payment").path("annotations").path("readOnlyHint").asBoolean(true)).isFalse()
    }

    @Disabled(
        "Deviation D12 (#16629): parameterless tools omit additionalProperties:false, which the spec recommends so a model cannot smuggle arguments",
    )
    @Test
    fun `parameterless tools reject extra arguments in their schema`() {
        val tools = bodyOf(send(request("tools/list"))).path("result").path("tools")
        tools.filter { it.path("inputSchema").path("properties").isEmpty }.forEach {
            assertThat(it.path("inputSchema").path("additionalProperties").asText()).`as`(it.path("name").asText())
                .isEqualTo("false")
        }
    }

    private companion object {
        val TOOL_NAMES = listOf(
            "list_accounts",
            "get_balance",
            "list_transactions",
            "list_consents",
            "get_statement",
            "get_payment_confirmation",
            "count_marketing_consents",
            "propose_payment",
        )
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
        StatementReadPort,
        PaymentConfirmationReadPort,
        ProposalPort {
        /** Every port call lands here, so a test can prove "no data and no downstream call". */
        var calls = 0

        private fun payload(): JsonNode {
            calls++
            return m.createObjectNode().put("status", "ACTIVE").put("currency", "CZK")
        }
        override fun listAccounts(consentContext: ConsentContext): JsonNode = payload()
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode = payload()
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
            payload()
        override fun listConsents(consentContext: ConsentContext): JsonNode = payload()
        override fun getStatementSummary(
            consentContext: ConsentContext,
            accountId: String,
            currency: String?,
            legalSequence: Long?,
        ): JsonNode = payload()
        override fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String): JsonNode = payload()
        override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode {
            calls++
            return m.createObjectNode().put("status", "PROPOSED")
        }
    }
}
