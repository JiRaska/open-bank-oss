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
    private fun endpoint(
        pdp: PolicyDecisionPoint,
        jwt: TestJsonWebToken = testAgentJwt(),
        toolsListCacheTtlMs: Long = 0,
        oboEnabled: Boolean = false,
    ): McpEndpoint {
        val stub = StubReads(mapper)
        val toolRegistry = McpToolRegistry(stub, stub, StubMarketingReachPort(mapper), McpPiiMasker(mapper), mapper)
        val caller = CallerContextResolver(jwt, oboEnabled)
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
            toolsCatalog = PolicyFilteredToolCatalog(toolRegistry, pdp, toolsListCacheTtlMs)
        }
    }

    /**
     * One limiter shared by every endpoint a test builds, so a test that wants to exhaust the
     * window can, and every other test gets the production defaults (60/min) — well above the
     * handful of calls they each make.
     */
    private val limiter = McpRateLimiter()

    // ── ADR-0226: every MCP event is stamped with its channel and delegation chain ──────────

    @Test
    fun `a tool call carries the cross-channel audit dimensions from the token`() {
        endpoint(allowAll(), jwt = oboJwt()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.channel).isEqualTo("mcp")
        assertThat(event.actChain).containsExactly("agent-session:7f3a", "mcp-cli")
        assertThat(event.sessionId).isEqualTo("sess-123")
    }

    @Test
    fun `a tool call without delegation claims audits as a direct caller on the mcp channel`() {
        endpoint(allowAll()).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.channel).isEqualTo("mcp")
        assertThat(event.actChain).isEmpty()
        assertThat(event.sessionId).isNull()
    }

    @Test
    fun `tools list audit carries the channel and delegation chain too`() {
        endpoint(allowAll(), jwt = oboJwt()).handle(rpc("tools/list"))
        val event = audit.events.single()
        assertThat(event.channel).isEqualTo("mcp")
        assertThat(event.actChain).containsExactly("agent-session:7f3a", "mcp-cli")
        assertThat(event.sessionId).isEqualTo("sess-123")
    }

    @Test
    fun `an unparseable act claim degrades to a direct caller, never a denied call`() {
        val weirdJwt = TestJsonWebToken(
            mapOf("sub" to "agent:test-agent", "consent_id" to TEST_CONSENT_ID, "act" to "not-a-map"),
        )
        val resp = body(
            endpoint(allowAll(), jwt = weirdJwt).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("isError").asBoolean(false)).isFalse()
        assertThat(audit.events.single().actChain).isEmpty()
    }

    private fun oboJwt() = TestJsonWebToken(
        mapOf(
            "sub" to "agent:test-agent",
            "consent_id" to TEST_CONSENT_ID,
            "act" to mapOf("sub" to "agent-session:7f3a", "act" to mapOf("sub" to "mcp-cli")),
            "sid" to "sess-123",
        ),
    )

    // ── ADR-0224 phase 1b: staff OBO tokens (flag-gated) ─────────────────────────────────────

    @Test
    fun `with the obo flag off a staff token is anonymous, fail-closed`() {
        val resp = body(
            endpoint(allowAll(), jwt = staffOboJwt(), oboEnabled = false).handle(
                rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
            ).entity,
        )
        assertThat(resp.path("result").path("content").first().path("text").asText())
            .isEqualTo("Authorization unavailable")
    }

    @Test
    fun `with the obo flag on a staff token reaches the PDP as HUMAN with its bounded roles`() {
        val pdp = RecordingPdp()
        endpoint(pdp, jwt = staffOboJwt(), oboEnabled = true).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val principal = pdp.queries.single().principal
        assertThat(principal.type).isEqualTo("HUMAN")
        assertThat(principal.id).isEqualTo("jane.operator")
        assertThat(principal.roles).containsExactly("ROLE_OPERATOR")
    }

    @Test
    fun `a staff token call audits the azp as the first act-chain link and the sid as session`() {
        endpoint(allowAll(), jwt = staffOboJwt(), oboEnabled = true).handle(
            rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
        )
        val event = audit.events.single()
        assertThat(event.channel).isEqualTo("mcp")
        assertThat(event.actChain).startsWith("openbank-admin-ui")
        assertThat(event.sessionId).isEqualTo("staff-sess-1")
    }

    @Test
    fun `a staff token missing any of the four marks is anonymous even with the flag on`() {
        val variants = listOf(
            staffClaims - "azp",
            staffClaims - "sid",
            staffClaims - "aud",
            staffClaims - "realm_access",
            staffClaims + ("aud" to "some-other-service"),
        )
        variants.forEach { claims ->
            val resp = body(
                endpoint(allowAll(), jwt = TestJsonWebToken(claims), oboEnabled = true).handle(
                    rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>())),
                ).entity,
            )
            assertThat(resp.path("result").path("content").first().path("text").asText())
                .isEqualTo("Authorization unavailable")
        }
    }

    private val staffClaims = mapOf(
        "sub" to "jane.operator",
        "azp" to "openbank-admin-ui",
        "sid" to "staff-sess-1",
        "aud" to "openbank-mcp-service",
        "realm_access" to mapOf("roles" to listOf("ROLE_OPERATOR", "default-roles-openbank")),
    )

    private fun staffOboJwt() = TestJsonWebToken(staffClaims)

    private class RecordingPdp : PolicyDecisionPoint {
        val queries = mutableListOf<AuthzQuery>()
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            queries += query
            return AuthzDecision(allow = true)
        }
    }

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

    // ── ADR-0225: discovery is capability-shaped, audited, fail-closed ───────────────────────

    @Test
    fun `tools list returns only the tools the PDP permits`() {
        val resp = body(
            endpoint(allowOnly("query.account.readonly", "query.balance.readonly")).handle(rpc("tools/list")).entity,
        )
        val names = resp.path("result").path("tools").map { it.path("name").asText() }
        assertThat(names)
            .containsExactlyInAnyOrder("list_accounts", "get_balance")
            .doesNotContain("list_transactions", "propose_payment", "count_marketing_consents")
    }

    @Test
    fun `tools list keeps schemas identical for every caller — only membership is filtered`() {
        val filtered = body(
            endpoint(allowOnly("query.account.readonly")).handle(rpc("tools/list")).entity,
        )
        val full = body(endpoint(allowAll()).handle(rpc("tools/list")).entity)
        val filteredSchema = filtered.path("result").path("tools").single().path("inputSchema")
        val fullSchema = full.path("result").path("tools")
            .first { it.path("name").asText() == "list_accounts" }.path("inputSchema")
        assertThat(filteredSchema).isEqualTo(fullSchema)
    }

    @Test
    fun `tools list is empty but successful when the PDP denies everything`() {
        val resp = body(endpoint(denyAll()).handle(rpc("tools/list")).entity)
        assertThat(resp.path("result").path("tools").size()).isZero()
        val event = audit.events.single()
        assertThat(event.operation).isEqualTo("mcp.tools.list")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload)
            .containsEntry("tools_returned", 0)
            .containsEntry("tools_total", 6)
    }

    @Test
    fun `tools list fails closed empty on a PDP outage and is audited as denied`() {
        val resp = body(endpoint(exploding()).handle(rpc("tools/list")).entity)
        assertThat(resp.path("result").path("tools").size()).isZero()
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload).containsEntry("pdp_errors", 6)
        assertThat(
            registry.get("openbank.mcp.tools_list")
                .tag("service", "mcp").tag("outcome", "pdp_unavailable").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `tools list with no agent token is empty and audited as an auth failure`() {
        val resp = body(
            endpoint(allowAll(), jwt = TestJsonWebToken()).handle(rpc("tools/list")).entity,
        )
        assertThat(resp.path("result").path("tools").size()).isZero()
        val event = audit.events.single()
        assertThat(event.operation).isEqualTo("mcp.tools.list")
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload).containsEntry("reason", "caller authentication failed")
        assertThat(
            registry.get("openbank.mcp.tools_list")
                .tag("service", "mcp").tag("outcome", "anonymous_denied").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `tools list is audited with the filter counts on the happy path`() {
        endpoint(allowAll()).handle(rpc("tools/list"))
        val event = audit.events.single()
        assertThat(event.operation).isEqualTo("mcp.tools.list")
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload)
            .containsEntry("tools_returned", 6)
            .containsEntry("tools_total", 6)
            .containsEntry("charter", "test-agent")
    }

    @Test
    fun `tools list is served from the per-principal cache within the TTL`() {
        val counting = CountingPdp()
        val ep = endpoint(counting, toolsListCacheTtlMs = 60_000)

        ep.handle(rpc("tools/list"))
        val afterFirst = counting.calls.get()
        ep.handle(rpc("tools/list"))

        assertThat(afterFirst).isEqualTo(6)
        assertThat(counting.calls.get()).isEqualTo(6)
        assertThat(audit.events).hasSize(2)
    }

    @Test
    fun `tools list re-consults the PDP once the cache TTL lapses`() {
        val counting = CountingPdp()
        val ep = endpoint(counting, toolsListCacheTtlMs = 0)

        ep.handle(rpc("tools/list"))
        ep.handle(rpc("tools/list"))

        assertThat(counting.calls.get()).isEqualTo(12)
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

    private fun allowOnly(vararg capabilities: String) = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = query.action in capabilities)
    }

    private class CountingPdp : PolicyDecisionPoint {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            calls.incrementAndGet()
            return AuthzDecision(allow = true)
        }
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
