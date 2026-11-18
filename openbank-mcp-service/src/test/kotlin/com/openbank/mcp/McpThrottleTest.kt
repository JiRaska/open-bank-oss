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
import com.openbank.mcp.application.port.out.PaymentConfirmationReadPort
import com.openbank.mcp.application.port.out.ProposalPort
import com.openbank.mcp.application.port.out.StatementReadPort
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0383 D5 ordering and bounding: the throttle runs before the PDP, for resolved and anonymous
 * callers and for both tools/call and tools/list; a flood is metered per request but audited at most
 * once per window; in-flight slots are released on every exit path.
 */
class McpThrottleTest {

    private val mapper = jacksonObjectMapper()
    private val audit = Recorder()
    private val meters = SimpleMeterRegistry()
    private val limiter = McpRateLimiter.withDefaults()
    private val accounts = mockk<AccountReadPort>(relaxed = true).also {
        every { it.listAccounts(any()) } returns mapper.createArrayNode()
    }

    private class Recorder : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
    }

    private class RecordingPdp(private val failing: () -> Boolean = { false }) : PolicyDecisionPoint {
        val queries = mutableListOf<AuthzQuery>()
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            queries += query
            check(!failing()) { "pdp down" }
            return AuthzDecision(allow = true)
        }
    }

    private fun endpoint(pdp: PolicyDecisionPoint, jwt: TestJsonWebToken = agentJwt()): McpEndpoint {
        val registry = McpToolRegistry(
            accounts,
            mockk<StatementReadPort>(relaxed = true),
            mockk<PaymentConfirmationReadPort>(relaxed = true),
            mockk<ProposalPort>(relaxed = true),
            StubMarketingReachPort(mapper),
            McpPiiMasker(mapper),
            mapper,
        )
        return McpEndpoint(
            registry = registry,
            pdp = pdp,
            auditor = McpCallAuditor(audit),
            callerResolver = CallerContextResolver(jwt, false, mockk<AgentSessionRepository>(relaxed = true)),
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            metrics = McpMetricsAdapter(meters)
            rateLimiter = limiter
            toolsCatalog = PolicyFilteredToolCatalog(registry, pdp, 0)
        }
    }

    private fun agentJwt() = TestJsonWebToken(
        mapOf(
            "sub" to AGENT_ID,
            "aud" to "openbank-mcp-service",
            "consent_id" to "11111111-1111-1111-1111-111111111111",
        ),
    )

    private fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.valueToTree(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private val listAccountsCall =
        rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>()))

    private fun text(resp: Response): String {
        val body = mapper.valueToTree<JsonNode>(resp.entity)
        return body.path("result").path("content").firstOrNull()?.path("text")?.asText()
            ?: body.path("error").path("message").asText()
    }

    private fun throttled(surface: String, caller: String) = meters.find("openbank.mcp.throttled")
        .tag("surface", surface).tag("caller", caller).counter()?.count() ?: 0.0

    @Test
    fun `a throttled call makes zero PDP calls`() {
        val pdp = RecordingPdp()
        val ep = endpoint(pdp)
        limiter.callsPerMinute = 1

        ep.handle(listAccountsCall)
        assertThat(pdp.queries).hasSize(1)
        repeat(5) { assertThat(text(ep.handle(listAccountsCall))).contains("Rate limit exceeded") }

        assertThat(pdp.queries).hasSize(1)
    }

    @Test
    fun `an unmapped tool also spends the callers window`() {
        val ep = endpoint(RecordingPdp())
        limiter.callsPerMinute = 1
        val unmapped = rpc("tools/call", mapOf("name" to "delete_everything", "arguments" to emptyMap<String, Any>()))

        ep.handle(unmapped)

        assertThat(text(ep.handle(unmapped))).contains("Rate limit exceeded")
    }

    @Test
    fun `unauthenticated callers share one small bucket and never reach the PDP`() {
        val pdp = RecordingPdp()
        val ep = endpoint(pdp, jwt = TestJsonWebToken())
        limiter.anonymousCallsPerMinute = 2

        repeat(2) { assertThat(text(ep.handle(listAccountsCall))).isEqualTo("Authorization unavailable") }
        assertThat(text(ep.handle(listAccountsCall))).contains("Rate limit exceeded")

        assertThat(pdp.queries).isEmpty()
    }

    @Test
    fun `the anonymous bucket does not spend an authenticated callers window`() {
        limiter.anonymousCallsPerMinute = 1
        endpoint(RecordingPdp(), jwt = TestJsonWebToken()).handle(listAccountsCall)

        assertThat(text(endpoint(RecordingPdp()).handle(listAccountsCall))).doesNotContain("Rate limit")
    }

    @Test
    fun `an anonymous flood writes no per-request audit or identity meter once throttled`() {
        val ep = endpoint(RecordingPdp(), jwt = TestJsonWebToken())
        limiter.anonymousCallsPerMinute = 1

        ep.handle(listAccountsCall)
        val rowsAfterFirst = audit.events.size
        repeat(20) { ep.handle(listAccountsCall) }

        // 20 refusals: every one metered, but at most ONE audit row for the window.
        assertThat(throttled("tools_call", "anonymous")).isEqualTo(20.0)
        assertThat(audit.events.size - rowsAfterFirst).isEqualTo(1)
        assertThat(
            meters.get("openbank.mcp.caller_identity").tag("source", "resolution_failed").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a throttled authenticated flood is metered every time and audited once per window`() {
        val ep = endpoint(RecordingPdp())
        limiter.callsPerMinute = 1

        ep.handle(listAccountsCall)
        val rows = audit.events.size
        repeat(10) { ep.handle(listAccountsCall) }

        assertThat(throttled("tools_call", "authenticated")).isEqualTo(10.0)
        assertThat(audit.events.size - rows).isEqualTo(1)
        assertThat(audit.events.last().result).isEqualTo(AuditResult.DENIED)
        assertThat(
            meters.get("openbank.mcp.tool_calls").tag("decision", "DENY").tag("result", "DENIED").counter().count(),
        ).isEqualTo(10.0)
    }

    @Test
    fun `tools list is throttled before any PDP call for resolved and anonymous callers`() {
        val pdp = RecordingPdp()
        limiter.callsPerMinute = 1
        limiter.anonymousCallsPerMinute = 1
        val known = endpoint(pdp)
        val anon = endpoint(pdp, jwt = TestJsonWebToken())

        known.handle(rpc("tools/list"))
        val pdpAfterFirst = pdp.queries.size
        val refused = text(known.handle(rpc("tools/list")))
        anon.handle(rpc("tools/list"))
        val refusedAnon = text(anon.handle(rpc("tools/list")))

        assertThat(refused).contains("Rate limit exceeded")
        assertThat(refusedAnon).contains("Rate limit exceeded")
        assertThat(pdp.queries.size).isEqualTo(pdpAfterFirst)
        assertThat(throttled("tools_list", "authenticated")).isEqualTo(1.0)
        assertThat(throttled("tools_list", "anonymous")).isEqualTo(1.0)
    }

    @Test
    fun `a caller at its in-flight cap is throttled before the PDP and the slot frees afterwards`() {
        val pdp = RecordingPdp()
        val ep = endpoint(pdp)
        limiter.maxInFlight = 1
        assertThat(limiter.tryAcquire(AGENT_ID)).isTrue()

        assertThat(text(ep.handle(listAccountsCall))).contains("too many concurrent calls")
        assertThat(pdp.queries).isEmpty()
        assertThat(audit.events.last().result).isEqualTo(AuditResult.DENIED)

        limiter.release(AGENT_ID)
        repeat(3) { assertThat(text(ep.handle(listAccountsCall))).doesNotContain("Rate limit") }
        assertThat(pdp.queries).hasSize(3)
    }

    @Test
    fun `the in-flight slot is released when the PDP throws`() {
        limiter.maxInFlight = 1
        var failing = true
        val ep = endpoint(RecordingPdp { failing })

        assertThat(text(ep.handle(listAccountsCall))).isEqualTo("Authorization unavailable")
        failing = false

        assertThat(text(ep.handle(listAccountsCall))).doesNotContain("Rate limit")
    }

    @Test
    fun `the in-flight slot is released when the tool throws`() {
        limiter.maxInFlight = 1
        every { accounts.listAccounts(any()) } throws IllegalStateException("boom") andThen mapper.createArrayNode()
        val ep = endpoint(RecordingPdp())

        assertThat(text(ep.handle(listAccountsCall))).isEqualTo("tool error")

        assertThat(text(ep.handle(listAccountsCall))).doesNotContain("Rate limit")
    }

    private companion object {
        const val AGENT_ID = "agent:test-agent"
    }
}
