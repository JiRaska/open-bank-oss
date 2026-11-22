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
 * #16590 staff root: the DPoP scheme is detected per RFC 7235 (case-insensitive, ended by SP, HTAB or
 * end of value) and refused 401; an `Authorization` header that cannot be read is refused 400 — FAIL
 * CLOSED, never served as anonymous. Ported from #16800's McpStaffRootCredentialParsingTest.
 */
class McpStaffRootCredentialParsingTest {
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

    private fun request(vararg authorization: String) =
        TestHttpRequests.withHeaders(*authorization.map { "Authorization" to it }.toTypedArray())

    private fun status(vararg authorization: String) =
        endpoint(request(*authorization)).handle(rpc("tools/list")).status

    @Test
    fun `control - no Authorization header is dispatched`() {
        assertThat(status()).isEqualTo(200)
    }

    @Test
    fun `a legitimate Bearer JWT passes, the scheme case-insensitively`() {
        listOf("Bearer $JWT", "bearer $JWT", "BEARER $JWT", "Bearer abc+/~-._=", "Bearer abc==").forEach {
            assertThat(status(it)).describedAs(it).isEqualTo(200)
        }
    }

    @Test
    fun `with no HTTP request in scope the staff root refuses 400 (fail closed)`() {
        assertThat(endpoint(request = null).handle(rpc("tools/list")).status).isEqualTo(400)
    }

    @Test
    fun `an Authorization header that throws on read is refused 400, never admitted`() {
        val throwing = mockk<HttpServerRequest>(relaxed = true) {
            every { headers() } throws IllegalStateException("broken request")
            every { getHeader(any<String>()) } throws IllegalStateException("broken request")
        }
        listOf(call, rpc("tools/list")).forEach { body ->
            assertThat(endpoint(throwing).handle(body).status).isEqualTo(400)
        }
    }

    @Test
    fun `the DPoP scheme in any case and after any whitespace is the customer refusal 401`() {
        listOf(
            "DPoP token",
            "DPoP\ttoken",
            "dpop token",
            "  DPOP\ttoken",
            "DPoP",
            "dPoP ",
            "DPoP\u00A0x",
            "DPoP\u2003x",
        )
            .forEach { assertThat(status(it)).describedAs(it).isEqualTo(401) }
    }

    @Test
    fun `every shape outside Bearer b64token is malformed 400`() {
        listOf(
            "Bearer a, DPoP b",
            "Bearer x, Bearer y",
            "Bearer\u00A0$JWT",
            "Bearer\u2003$JWT",
            "Bearer\t$JWT",
            "Bearer  $JWT",
            " Bearer $JWT",
            "Bearer $JWT ",
            "Bearer ",
            "Bearer a=b",
            "Basic dXNlcjpwYXNz",
            "DPoPx token",
            "Token x",
        ).forEach { assertThat(status(it)).describedAs(it).isEqualTo(400) }
    }

    @Test
    fun `two Authorization headers are refused 400`() {
        assertThat(status("Bearer a", "Bearer b")).isEqualTo(400)
    }

    private object NoSessions : AgentSessionRepository() {
        override suspend fun findActiveByJti(jti: String, asOf: java.time.Instant): AgentSessionEntity? = null
    }

    private companion object {
        const val JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZ2VudDp0ZXN0In0.c2lnbmF0dXJlLXZhbHVl-_"
    }
}
