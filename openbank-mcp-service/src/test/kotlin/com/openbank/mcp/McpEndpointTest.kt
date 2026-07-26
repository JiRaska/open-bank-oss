// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-unit coverage of the MCP JSON-RPC dispatch + the deny-by-default OPA gate (ADR-0181). No
 * Quarkus/Vert.x context — the endpoint is a POJO with injected collaborators, so the protocol and
 * the authorization decision are provable without a running server or a real PDP.
 */
class McpEndpointTest {

    private val mapper = jacksonObjectMapper()

    private val audit = Recorder()

    // The REAL metrics adapter over a SimpleMeterRegistry rather than a mock port, so the
    // instrumentation assertions below fail if the endpoint stops emitting.
    private val registry = SimpleMeterRegistry()

    // A validated agent token (sub + consent_id) — ADR-0195 step 4 removed the phase-1 placeholder
    // fallback, so every test that exercises protocol dispatch / the PDP gate / audit / metering
    // needs a real-shaped identity to reach `handleToolCall` at all. The dedicated "no token" test
    // below builds its own endpoint with an anonymous JWT to cover that (now denying) path instead.
    private fun endpoint(pdp: PolicyDecisionPoint, jwt: TestJsonWebToken = testAgentJwt()): McpEndpoint {
        val stub = StubReads(mapper)
        val toolRegistry = McpToolRegistry(stub, stub, mapper)
        val caller = CallerContextResolver(jwt)
        return McpEndpoint(
            registry = toolRegistry,
            pdp = pdp,
            auditor = McpCallAuditor(audit),
            callerResolver = caller,
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            metrics = McpMetricsAdapter(registry)
            rateLimiter = limiter
        }
    }

    /**
     * One limiter shared by every endpoint a test builds, so a test that wants to exhaust the
     * window can, and every other test gets the production defaults (60/min) — well above the
     * handful of calls they each make.
     */
    private val limiter = McpRateLimiter()

    @Test
    fun `tools call is throttled once the acting agent exhausts its per-minute window`() {
        val ep = endpoint(allowAll())
        limiter.callsPerMinute = 2
        val call = rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>()))

        repeat(2) { assertThat(body(ep.handle(call).entity).path("result").path("isError").asBoolean(false)).isFalse() }
        val throttled = body(ep.handle(call).entity)

        assertThat(throttled.path("result").path("isError").asBoolean()).isTrue()
        assertThat(throttled.path("result").path("content").first().path("text").asText())
            .contains("Rate limit exceeded")
        // A throttled call is auditable like any other denial — the trail must not go quiet on it.
        assertThat(audit.events.last().result).isEqualTo(AuditResult.DENIED)
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

    // ── ADR-0031 D5: every tools/call outcome is on the record ──────────────────────────────

    @Test
    fun `an allowed tool call is audited as ALLOW + SUCCESS`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to mapOf("unused" to "x"))),
        )
        val event = audit.events.single()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.operation).isEqualTo("mcp.tool.call")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload)
            .containsEntry("policy_decision", "ALLOW")
            .containsEntry("capability", "query.account.readonly")
            .containsEntry("charter", "test-agent")
            .containsEntry("argument_keys", listOf("unused"))
    }

    @Test
    fun `a policy-denied tool call is audited as DENY + DENIED with the reason`() {
        endpoint(denyAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload)
            .containsEntry("policy_decision", "DENY")
            .containsEntry("reason", "no matching allow rule")
    }

    @Test
    fun `an unmapped tool is audited even though the PDP is never consulted`() {
        endpoint(exploding()).handle(
            rpc("tools/call", mapOf("name" to "delete_everything", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.resourceId).isEqualTo("delete_everything")
        assertThat(event.payload)
            .containsEntry("capability", null)
            .containsEntry("reason", "no capability mapping")
    }

    @Test
    fun `a PDP outage is audited as UNAVAILABLE, distinct from a policy DENY`() {
        endpoint(exploding()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload).containsEntry("policy_decision", "UNAVAILABLE")
    }

    @Test
    fun `the audit payload never carries tool arguments or tool output`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "get_balance", "arguments" to mapOf("accountId" to SECRET))),
        )
        val rendered = audit.events.single().payload.toString()
        assertThat(rendered).contains("accountId").doesNotContain(SECRET)
    }

    // ── ADR-0077 Tier C: the same outcomes as an aggregate ──────────────────────────────────

    @Test
    fun `an allowed tool call is metered with the same three facts as its audit event`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )

        assertThat(toolCalls("list_accounts", "ALLOW", "SUCCESS")).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.mcp.tool_call.duration")
                .tag("service", "mcp").tag("tool", "list_accounts").timer().count(),
        ).isEqualTo(1L)
    }

    @Test
    fun `a PDP outage is metered as UNAVAILABLE, distinct from a policy DENY`() {
        // Fail-closed on a money-adjacent surface denies EVERY agent. Correct, and previously
        // nothing but a WARN line.
        endpoint(exploding()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        endpoint(denyAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )

        assertThat(toolCalls("list_accounts", "UNAVAILABLE", "DENIED")).isEqualTo(1.0)
        assertThat(toolCalls("list_accounts", "DENY", "DENIED")).isEqualTo(1.0)
    }

    @Test
    fun `an unmapped tool is metered as tool=unmapped, never under its caller-supplied name`() {
        // Cardinality contract: the tool name arrives in the request body on a public agent surface,
        // so an agent enumerating the tool surface must not be able to mint a series per guess. The
        // audit event still records the exact name — that is a per-call record, not a label.
        endpoint(exploding()).handle(
            rpc("tools/call", mapOf("name" to "delete_everything", "arguments" to emptyMap<String, Any>())),
        )

        assertThat(toolCalls("unmapped", "DENY", "DENIED")).isEqualTo(1.0)
        assertThat(registry.find("openbank.mcp.tool_calls").tag("tool", "delete_everything").counters()).isEmpty()
        assertThat(audit.events.single().resourceId).isEqualTo("delete_everything")
    }

    @Test
    fun `an unknown JSON-RPC method is metered as method=unknown`() {
        endpoint(allowAll()).handle(rpc("tools/list"))
        endpoint(allowAll()).handle(rpc("evil/method"))

        assertThat(requests("tools/list")).isEqualTo(1.0)
        assertThat(requests("unknown")).isEqualTo(1.0)
        assertThat(registry.find("openbank.mcp.requests").tag("method", "evil/method").counters()).isEmpty()
    }

    @Test
    fun `a call under a validated token is metered as source=token`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )

        assertThat(
            registry.get("openbank.mcp.caller_identity")
                .tag("service", "mcp").tag("source", "token").counter().count(),
        ).isEqualTo(1.0)
        assertThat(registry.find("openbank.mcp.caller_identity").tag("source", "anonymous_fallback").counters())
            .isEmpty()
    }

    // ADR-0195 step 4 (BLOCKER #2206): the phase-1 placeholder identity is gone — a call with no
    // agent token must be denied, never silently allowed. Own endpoint instance with an anonymous
    // JWT (no `sub`), since every other test in this file deliberately presents a real token.
    @Test
    fun `a tool call with no agent token is denied and metered as resolution_failed`() {
        val resp = body(
            endpoint(allowAll(), jwt = TestJsonWebToken()).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )

        assertThat(resp.path("result").path("isError").asBoolean()).isTrue()
        assertThat(
            resp.path("result").path("content").first().path("text").asText(),
        ).isEqualTo("Authorization unavailable")
        assertThat(audit.events.single().result).isEqualTo(AuditResult.DENIED)
        assertThat(audit.events.single().payload).containsEntry("reason", "caller authentication failed")
        assertThat(
            registry.get("openbank.mcp.caller_identity")
                .tag("service", "mcp").tag("source", "resolution_failed").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `no meter carries the agent id, consent id or an argument value`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "get_balance", "arguments" to mapOf("accountId" to SECRET))),
        )

        val tags = registry.meters.flatMap { it.id.tags }
        assertThat(tags.map { it.key }).doesNotContain("agent_id", "consent_id", "charter", "account_id")
        assertThat(tags.map { it.value }).doesNotContain(SECRET, "agent:test-agent")
    }

    private fun toolCalls(tool: String, decision: String, result: String): Double =
        registry.get("openbank.mcp.tool_calls")
            .tag("service", "mcp")
            .tag("tool", tool)
            .tag("decision", decision)
            .tag("result", result)
            .counter().count()

    private fun requests(method: String): Double =
        registry.get("openbank.mcp.requests").tag("service", "mcp").tag("method", method).counter().count()

    private class Recorder : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
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

    // A validated agent token (ADR-0195): sub carries the `agent:` prefix the shared
    // AuthorizeInterceptor/rego convention expects; consent_id is required by CallerContextResolver
    // (a present-but-blank/missing claim fails closed, not silently).
    private fun testAgentJwt() = TestJsonWebToken(mapOf("sub" to "agent:test-agent", "consent_id" to TEST_CONSENT_ID))

    private companion object {
        const val SECRET = "CZ6508000000192000145399"
        const val TEST_CONSENT_ID = "11111111-1111-1111-1111-111111111111"
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
