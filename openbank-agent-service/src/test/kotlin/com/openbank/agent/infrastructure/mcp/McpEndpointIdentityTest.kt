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
import com.openbank.agent.application.SvidResult
import com.openbank.agent.domain.McpResponse
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolDefinition
import com.openbank.agent.domain.ToolsListResult
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.agent.domain.policy.EnforcementMode
import com.openbank.agent.domain.policy.GateOutcome
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.HttpHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.Optional

/**
 * ADR-0031 D3: the /mcp surface must not let an authenticated operator assume an agent identity
 * their verified roles don't authorize. Exercises the real `handle()` path with a mocked
 * SecurityIdentity / headers.
 */
class McpEndpointIdentityTest {

    private val mapper = jacksonObjectMapper()
    private val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)

    private val registry = mockk<McpToolRegistry> {
        every { tools } returns listOf(
            ToolDefinition("get_account", "get account", emptyMap()),
            ToolDefinition("aml_list_cases", "list AML cases", emptyMap()),
        )
        every { capabilityOf("get_account") } returns "query.ledger.readonly"
        every { capabilityOf("aml_list_cases") } returns "query.compliance.readonly"
        every { serviceOf(any()) } returns null
        every { domainOf(any()) } returns null
    }

    private val charters = mockk<CharterRegistry> {
        every { allowedCapabilities("ui-assistant") } returns setOf("query.ledger.readonly")
        every { allowedCapabilities("compliance-officer") } returns setOf("query.compliance.readonly")
    }

    private fun endpoint(
        headerAgentId: String?,
        callerRoles: Set<String>,
        anonymous: Boolean,
        policyGate: AgentPolicyGate = mockk(),
        enforced: Boolean = true,
        svidResult: SvidResult = SvidResult.Disabled,
        svidEnforced: Boolean = false,
    ): McpEndpoint {
        val hdr = mockk<HttpHeaders> {
            every { getHeaderString(any()) } returns null
            every { getHeaderString("X-Agent-Id") } returns headerAgentId
            // When a non-Disabled SVID result is requested, stub cert headers so verify() fires.
            if (svidResult !is SvidResult.Disabled) {
                every { getHeaderString("X-Agent-Cert") } returns "cert"
                every { getHeaderString("X-Agent-PoP") } returns "pop"
                every { getHeaderString("X-Agent-PoP-Ts") } returns "1000"
                every { getHeaderString("X-Agent-PoP-Nonce") } returns "nonce"
            }
        }
        val ident = mockk<SecurityIdentity> {
            every { isAnonymous } returns anonymous
            every { getRoles() } returns callerRoles
            every { principal } returns Principal { "operator-1" }
        }
        val svidMock = mockk<AgentSvidVerifier> {
            every { verify(any(), any(), any(), any(), any()) } returns svidResult
        }
        return McpEndpoint().apply {
            this.registry = this@McpEndpointIdentityTest.registry
            this.objectMapper = mapper
            this.policyGate = policyGate
            this.charterRegistry = charters
            this.binding = AgentIdentityBinding(
                enforced,
                "ROLE_OPERATOR=ui-assistant;ROLE_COMPLIANCE=compliance-officer",
            )
            this.identity = ident
            this.auditPublisher = this@McpEndpointIdentityTest.auditPublisher
            this.svid = if (svidResult == SvidResult.Disabled) {
                // Use the real disabled verifier for the existing D3a tests (unchanged).
                AgentSvidVerifier(caCertPem = Optional.empty(), maxSkewSeconds = 60)
            } else {
                svidMock
            }
            this.svidEnforced = svidEnforced
            this.headers = hdr
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toolsOf(ep: McpEndpoint): List<ToolDefinition> {
        val body = mapper.readTree("""{"method":"tools/list","id":1}""")
        val resp = ep.handle(body)
        val result = (resp.entity as McpResponse).result as ToolsListResult
        return result.tools
    }

    @Test
    fun `a permitted operator sees only its charter's tools`() {
        val tools = toolsOf(endpoint("ui-assistant", setOf("ROLE_OPERATOR"), anonymous = false))
        assertThat(tools.map { it.name }).containsExactly("get_account")
    }

    @Test
    fun `a forbidden assumption yields an empty tool list and is audited`() {
        val tools = toolsOf(endpoint("compliance-officer", setOf("ROLE_OPERATOR"), anonymous = false))
        assertThat(tools).isEmpty()
        val evt = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(evt)) }
        assertThat(evt.captured.operation).isEqualTo("agent.identity.rejected")
        assertThat(evt.captured.resourceId).isEqualTo("compliance-officer")
    }

    @Test
    fun `anonymous (OIDC off in test) preserves legacy header trust`() {
        val tools = toolsOf(endpoint("compliance-officer", emptySet(), anonymous = true))
        assertThat(tools.map { it.name }).containsExactly("aml_list_cases")
        coVerify(exactly = 0) { auditPublisher.publish(any()) }
    }

    @Test
    fun `no header keeps the legacy full list`() {
        val tools = toolsOf(endpoint(null, setOf("ROLE_OPERATOR"), anonymous = false))
        assertThat(tools.map { it.name }).containsExactlyInAnyOrder("get_account", "aml_list_cases")
    }

    @Test
    fun `a forbidden assumption denies tools-call (deny-by-default, never reaches the tool)`() {
        val gate = mockk<AgentPolicyGate> {
            every { authorize(null, any(), any(), any()) } returns GateOutcome(
                decision = PolicyDecision(
                    allow = false,
                    agent = "anonymous",
                    tool = "query.compliance.readonly",
                    resource = null,
                    reason = "no agent identity asserted (deny-by-default)",
                ),
                mode = EnforcementMode.BLOCK,
                proceed = false,
            )
        }
        val ep = endpoint("compliance-officer", setOf("ROLE_OPERATOR"), anonymous = false, policyGate = gate)
        val body = mapper.readTree(
            """{"method":"tools/call","id":2,"params":{"name":"aml_list_cases","arguments":{}}}""",
        )
        val result = (ep.handle(body).entity as McpResponse).result as ToolCallResult
        assertThat(result.isError).isTrue()
    }

    // ── D3b CN→role cross-check (ADR-0031 hardening) ─────────────────────────────────────────────

    @Test
    fun `valid SVID with CN permitted by operator roles returns charter tools`() {
        val tools = toolsOf(
            endpoint(
                headerAgentId = null,
                callerRoles = setOf("ROLE_OPERATOR"),
                anonymous = false,
                svidResult = SvidResult.Verified("ui-assistant"),
            ),
        )
        assertThat(tools.map { it.name }).containsExactly("get_account")
    }

    @Test
    fun `valid SVID with CN NOT permitted by operator roles is rejected and audited`() {
        val tools = toolsOf(
            endpoint(
                headerAgentId = null,
                callerRoles = setOf("ROLE_OPERATOR"),
                anonymous = false,
                svidResult = SvidResult.Verified("compliance-officer"),
            ),
        )
        assertThat(tools).isEmpty()
        val evt = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(evt)) }
        assertThat(evt.captured.operation).isEqualTo("agent.identity.rejected")
        assertThat(evt.captured.payload).containsKey("method")
        assertThat(evt.captured.payload["method"]).isEqualTo("svid_cn_binding")
    }

    @Test
    fun `valid SVID in anonymous mode bypasses CN cross-check (dev-test parity)`() {
        val tools = toolsOf(
            endpoint(
                headerAgentId = null,
                callerRoles = emptySet(),
                anonymous = true,
                svidResult = SvidResult.Verified("compliance-officer"),
            ),
        )
        assertThat(tools.map { it.name }).containsExactly("aml_list_cases")
        coVerify(exactly = 0) { auditPublisher.publish(any()) }
    }

    @Test
    fun `svid-enforced with no cert rejects and audits, falls back to no identity`() {
        val tools = toolsOf(
            endpoint(
                headerAgentId = "ui-assistant",
                callerRoles = setOf("ROLE_OPERATOR"),
                anonymous = false,
                svidResult = SvidResult.Disabled,
                svidEnforced = true,
            ),
        )
        assertThat(tools).isEmpty()
        val evt = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(evt)) }
        assertThat(evt.captured.operation).isEqualTo("agent.identity.rejected")
    }

    @Test
    fun `charter model id is threaded into the identity passed to the policy gate`() {
        var capturedIdentity: AgentIdentity? = null
        val gate = mockk<AgentPolicyGate> {
            every { authorize(any(), any(), any(), any()) } answers {
                capturedIdentity = firstArg()
                GateOutcome(
                    decision = PolicyDecision(
                        allow = false,
                        agent = "compliance-officer",
                        tool = "query.compliance.readonly",
                        resource = null,
                        reason = "denied-for-test",
                    ),
                    mode = EnforcementMode.BLOCK,
                    proceed = false,
                )
            }
        }
        val chartersWithModel = mockk<CharterRegistry> {
            every { allowedCapabilities("compliance-officer") } returns setOf("query.compliance.readonly")
            every { modelId("compliance-officer") } returns "llama-3.3-70b-versatile"
        }
        val body = mapper.readTree(
            """{"method":"tools/call","id":2,"params":{"name":"aml_list_cases","arguments":{}}}""",
        )
        val ep = McpEndpoint().apply {
            this.registry = this@McpEndpointIdentityTest.registry
            this.objectMapper = mapper
            this.policyGate = gate
            this.charterRegistry = chartersWithModel
            this.binding = AgentIdentityBinding(false, "")
            this.identity =
                mockk {
                    every { isAnonymous } returns true
                    every { getRoles() } returns emptySet()
                    every { principal } returns
                        Principal { "op" }
                }
            this.auditPublisher = this@McpEndpointIdentityTest.auditPublisher
            this.svid = AgentSvidVerifier(caCertPem = Optional.empty(), maxSkewSeconds = 60)
            this.svidEnforced = false
            this.headers =
                mockk {
                    every { getHeaderString(any()) } returns null
                    every { getHeaderString("X-Agent-Id") } returns
                        "compliance-officer"
                }
        }
        ep.handle(body)
        assertThat(capturedIdentity?.modelId).isEqualTo("llama-3.3-70b-versatile")
    }
}
