// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.mcp.application.McpCallAuditor
import com.openbank.mcp.application.McpPiiMasker
import com.openbank.mcp.application.McpToolRegistry
import com.openbank.mcp.application.PolicyFilteredToolCatalog
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.persistence.AgentSessionEntity
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.http.HttpServerRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0383 D1, customer channel OFF: any customer credential is refused 401 before dispatch, and
 * the check FAILS CLOSED — a request whose headers cannot be read is refused, never admitted.
 */
class McpEndpointCustomerCredentialTest {
    private val mapper = jacksonObjectMapper()

    private fun endpoint(request: HttpServerRequest?): McpEndpoint {
        val registry = McpToolRegistry(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            StubMarketingReachPort(mapper),
            McpPiiMasker(mapper),
            mapper,
        )
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true)
        }
        val jwt = TestJsonWebToken(
            mapOf(
                "sub" to "agent:test-agent",
                "aud" to "openbank-mcp-service",
                "consent_id" to "11111111-1111-1111-1111-111111111111",
            ),
        )
        return McpEndpoint(
            registry = registry,
            pdp = pdp,
            auditor = McpCallAuditor(RecordingAuditEventPublisher()),
            callerResolver = CallerContextResolver(jwt, false, NoSessions, customerIssuer = java.util.Optional.empty()),
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            request?.let { httpRequest = it }
            metrics = McpMetricsAdapter(SimpleMeterRegistry())
            rateLimiter = McpRateLimiter.withDefaults()
            toolsCatalog = PolicyFilteredToolCatalog(registry, pdp, 0)
        }
    }

    private fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.valueToTree(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private val call = rpc("tools/call", mapOf("name" to "list_accounts", "arguments" to emptyMap<String, Any>()))

    @Test
    fun `control - a request with no customer credential is dispatched`() {
        assertThat(endpoint(TestHttpRequests.withHeaders()).handle(rpc("tools/list")).status).isEqualTo(200)
    }

    @Test
    fun `a request whose headers cannot be read is refused 401, never admitted (fail closed)`() {
        assertThat(endpoint(request = null).handle(call).status).isEqualTo(401)
    }

    @Test
    fun `an Authorization header that throws on read is refused, never admitted`() {
        val throwing = mockk<HttpServerRequest>(relaxed = true) {
            every { getHeader(any<String>()) } throws IllegalStateException("broken request")
        }
        assertThat(endpoint(throwing).handle(call).status).isEqualTo(401)
    }

    @Test
    fun `the DPoP scheme is refused case-insensitively with any RFC 7235 whitespace`() {
        listOf("DPoP token", "DPoP\ttoken", "dpop token", "  DPOP\ttoken").forEach { header ->
            val status = endpoint(
                TestHttpRequests.withHeaders("Authorization" to header),
            ).handle(rpc("tools/list")).status
            assertThat(status).describedAs(header).isEqualTo(401)
        }
        val bearer = endpoint(TestHttpRequests.withHeaders("Authorization" to "Bearer x")).handle(rpc("tools/list"))
        assertThat(bearer.status).isEqualTo(200)
    }

    private object NoSessions : AgentSessionRepository() {
        override suspend fun findActiveByJti(jti: String, asOf: java.time.Instant): AgentSessionEntity? = null
    }
}
