// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.application.AgentIdentityBinding
import com.openbank.agent.application.AgentPolicyGate
import com.openbank.agent.application.AgentSvidVerifier
import com.openbank.agent.application.CharterRegistry
import com.openbank.agent.application.McpToolRegistry
import com.openbank.agent.application.SanctionsReviewTool
import com.openbank.agent.domain.McpResponse
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolContent
import com.openbank.agent.domain.policy.EnforcementMode
import com.openbank.agent.domain.policy.GateOutcome
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.infrastructure.security.InMemoryNonceStore
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.HttpHeaders
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * #16455: the four-eyes maker tool learns WHO drives the MCP session only from a verified person
 * token. A machine bearer must reach the registry with no person, so the tool refuses.
 */
class McpEndpointInteractivePersonTest {

    private val mapper = jacksonObjectMapper()
    private val person = slot<String?>()

    private val registry = mockk<McpToolRegistry> {
        every { capabilityOf(any()) } returns "write.compliance.proposal"
        every { serviceOf(any()) } returns null
        every { domainOf(any()) } returns null
        every { call(any(), any(), any(), captureNullable(person)) } returns
            ToolCallResult(content = listOf(ToolContent(text = "ok")))
    }

    private val gate = mockk<AgentPolicyGate> {
        every { authorize(any(), any(), any(), any()) } returns GateOutcome(
            decision = PolicyDecision(
                allow = true,
                agent = "compliance-officer",
                tool = "t",
                resource = null,
                reason = "ok",
            ),
            mode = EnforcementMode.BLOCK,
            proceed = true,
        )
    }

    private fun jwt(claims: Map<String, String>) = mockk<JsonWebToken> {
        every { name } returns (claims["preferred_username"] ?: "nobody")
        every { getClaim<Any?>(any<String>()) } answers { claims[firstArg()] }
        every { claimNames } returns claims.keys
    }

    private fun endpoint(token: JsonWebToken) = McpEndpoint().apply {
        registry = this@McpEndpointInteractivePersonTest.registry
        objectMapper = mapper
        policyGate = gate
        charterRegistry = mockk<CharterRegistry> {
            every { allowedCapabilities(any()) } returns setOf("write.compliance.proposal")
            every { modelId(any()) } returns "test-model"
        }
        binding = AgentIdentityBinding(true, "ROLE_COMPLIANCE=compliance-officer")
        identity = mockk<SecurityIdentity> {
            every { isAnonymous } returns false
            every { getRoles() } returns setOf("ROLE_COMPLIANCE")
            every { principal } returns token
        }
        auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
        svid = AgentSvidVerifier(caCertPem = Optional.empty(), maxSkewSeconds = 60, nonceStore = InMemoryNonceStore())
        svidEnforced = false
        headers = mockk<HttpHeaders> {
            every { getHeaderString(any()) } returns null
            every { getHeaderString("X-Agent-Id") } returns "compliance-officer"
        }
    }

    private fun callReview(ep: McpEndpoint) {
        val body = mapper.readTree(
            """{"method":"tools/call","id":1,"params":{"name":"${SanctionsReviewTool.NAME}","arguments":{}}}""",
        )
        ep.handle(body).entity as McpResponse
    }

    @Test
    fun `a signed-in person reaches the review tool as the interactive person`() {
        callReview(endpoint(jwt(mapOf("preferred_username" to "officer-1", "azp" to "openbank-admin-ui"))))
        assertThat(person.captured).isEqualTo("officer-1")
    }

    @Test
    fun `a machine token reaches the review tool with no person, so the tool refuses`() {
        listOf(
            mapOf("preferred_username" to "service-account-openbank-agent", "azp" to "openbank-agent"),
            mapOf("preferred_username" to "officer-1", "client_id" to "openbank-agent"),
            mapOf("azp" to "openbank-agent"),
        ).forEach { claims ->
            callReview(endpoint(jwt(claims)))
            assertThat(person.captured).describedAs(claims.toString()).isNull()
        }
    }
}
