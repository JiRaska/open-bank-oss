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
import com.openbank.mcp.infrastructure.mcp.CallerContextResolver
import com.openbank.mcp.infrastructure.mcp.McpEndpoint
import com.openbank.mcp.infrastructure.observability.McpMetricsAdapter
import com.openbank.mcp.infrastructure.persistence.AgentSessionEntity
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import com.openbank.mcp.infrastructure.read.StubMarketingReachPort
import com.openbank.mcp.infrastructure.read.UnwiredProposalPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * T-E4 (#2414), the half that is NOT blocked on the `ProposalPort` architecture decision: the
 * shipped binding must not answer `PROPOSED` to a proposal it never recorded.
 *
 * Until this change `StubProposalPort` returned the literal `{"phase":"1-stub","status":"PROPOSED"}`
 * to every caller. There is no maker-checker queue behind it and no row anywhere, so a model reading
 * that answer — and the human it is talking to — is told a payment proposal is awaiting a checker
 * when nothing was written. That is not an unimplemented control; it is a control that reports
 * success. The repo's own precedent (#3613, #3826) is that a control which cannot work must refuse
 * rather than fake-enforce.
 *
 * These assertions bind the PRODUCTION wiring ([UnwiredProposalPort]), not a test double, because
 * the defect being fixed lives in the binding. Both are red against the pre-change tree: the
 * registry case returned a node, and the endpoint case returned a non-error result whose text
 * contained `PROPOSED`.
 */
class ProposalRefusalTest {

    private val mapper = jacksonObjectMapper()
    private val audit = Recorder()
    private val meters = SimpleMeterRegistry()

    private fun validArgs() = mapOf(
        "fromAccountId" to "acc-1",
        "toIban" to "CZ6508000000192000145399",
        "amount" to "12.34",
        "currency" to "CZK",
    )

    @Test
    fun `the shipped port refuses instead of returning a fabricated PROPOSED`() {
        assertThatThrownBy { UnwiredProposalPort().proposePayment(CTX, mapper.createObjectNode()) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("no proposal has been recorded")
    }

    @Test
    fun `the registry surfaces the refusal rather than a proposal document`() {
        assertThatThrownBy { registry().call("propose_payment", mapper.valueToTree(validArgs()), CTX) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `a caller gets an explicit refusal, and the word PROPOSED never reaches it`() {
        val response = endpoint().handle(
            mapper.valueToTree(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "propose_payment", "arguments" to validArgs()),
                ),
            ),
        )
        val body = mapper.writeValueAsString(response.entity)

        assertThat(body).contains("\"isError\":true")
        assertThat(body).contains("no proposal has been recorded")
        // The load-bearing assertion: the caller must not be able to read a proposal state back.
        assertThat(body).doesNotContain("PROPOSED")
    }

    @Test
    fun `the refusal is audited as a failure, not as a successful tool call`() {
        endpoint().handle(
            mapper.valueToTree(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "propose_payment", "arguments" to validArgs()),
                ),
            ),
        )
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.FAILURE)
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────────

    private fun registry() = McpToolRegistry(
        accounts = UnusedAccountReadPort,
        proposals = UnwiredProposalPort(),
        marketingReach = StubMarketingReachPort(mapper),
        masker = McpPiiMasker(mapper),
        mapper = mapper,
    )

    private fun endpoint(): McpEndpoint {
        val toolRegistry = registry()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery) = AuthzDecision(allow = true)
        }
        val jwt = TestJsonWebToken(
            mapOf("sub" to "agent:test-agent", "consent_id" to "11111111-1111-1111-1111-111111111111"),
        )
        return McpEndpoint(
            registry = toolRegistry,
            pdp = pdp,
            auditor = McpCallAuditor(audit),
            callerResolver = CallerContextResolver(jwt, false, NoSessions),
            mapper = mapper,
            serverName = "openbank-mcp",
            serverVersion = "0.1.0",
            protocolVersion = "2025-06-18",
        ).apply {
            metrics = McpMetricsAdapter(meters)
            rateLimiter = McpRateLimiter()
            toolsCatalog = PolicyFilteredToolCatalog(toolRegistry, pdp, 0)
        }
    }

    private object NoSessions : AgentSessionRepository() {
        override suspend fun findActiveByJti(jti: String, asOf: java.time.Instant): AgentSessionEntity? = null
    }

    private class Recorder : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
    }

    private object UnusedAccountReadPort : AccountReadPort {
        override fun listAccounts(consentContext: ConsentContext): JsonNode = error("not used")
        override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode = error("not used")
        override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
            error("not used")
        override fun listConsents(consentContext: ConsentContext): JsonNode = error("not used")
    }

    private companion object {
        private val CTX = ConsentContext(
            agentId = "agent:test-agent",
            consentId = "11111111-1111-1111-1111-111111111111",
            grantedAccounts = listOf("acc-1"),
        )
    }
}
