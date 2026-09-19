// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.policy

import com.openbank.agent.domain.policy.PolicyQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The two PDP adapters, exercised at the edge that matters: neither may ever produce an implicit
 * allow, and a transport failure must be distinguishable from an explicit policy DENY
 * (`pdpError`), because AgentPolicyGate keys its BLOCK -> ADVISORY fallback on that flag rather
 * than on the free-form reason text.
 */
class PolicyDecisionPointAdapterTest {

    private val query = PolicyQuery(
        agent = "ui-assistant",
        tool = "aml_list_cases",
        resource = "case-1",
        plane = "data",
        attributes = mapOf("skill" to "read"),
    )

    @Test
    fun `the fallback PDP denies every query and echoes the subject back for the audit line`() {
        val decision = DenyByDefaultPolicyDecisionPoint().evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.agent).isEqualTo("ui-assistant")
        assertThat(decision.tool).isEqualTo("aml_list_cases")
        assertThat(decision.resource).isEqualTo("case-1")
        assertThat(decision.reason).contains("deny-by-default")
        // Not a transport failure: the gate must NOT downgrade this to advisory.
        assertThat(decision.pdpError).isFalse()
    }

    private fun opa(client: OpaClient) = OpaPolicyDecisionPoint().also { it.opa = client }

    @Test
    fun `OPA allow is carried through with the policy's own reason`() {
        val client = mockk<OpaClient>()
        every { client.decision(any()) } returns OpaResponse(OpaDecision(allow = true, reason = "charter allows"))

        val decision = opa(client).evaluate(query)

        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).isEqualTo("charter allows")
        assertThat(decision.pdpError).isFalse()
    }

    @Test
    fun `a reasonless OPA answer gets a default reason so no decision is ever silent`() {
        val client = mockk<OpaClient>()
        every { client.decision(any()) } returns OpaResponse(OpaDecision(allow = true, reason = null))
        assertThat(opa(client).evaluate(query).reason).isEqualTo("allowed by policy")

        every { client.decision(any()) } returns OpaResponse(OpaDecision(allow = false, reason = null))
        val denied = opa(client).evaluate(query)
        assertThat(denied.allow).isFalse()
        assertThat(denied.reason).isEqualTo("denied by policy")
    }

    @Test
    fun `a missing result body is a DENY, not an allow, and is not flagged as a transport error`() {
        val client = mockk<OpaClient>()
        every { client.decision(any()) } returns OpaResponse(result = null)

        val decision = opa(client).evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).isEqualTo("OPA returned no decision")
        assertThat(decision.pdpError).isFalse()
    }

    @Test
    fun `an unreachable sidecar fails closed AND sets pdpError so BLOCK can fall back to advisory`() {
        val client = mockk<OpaClient>()
        every { client.decision(any()) } throws IOException("connection refused")

        val decision = opa(client).evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.pdpError).isTrue()
        assertThat(decision.reason).contains("connection refused")
    }

    @Test
    fun `the OPA input carries the whole ADR-0031 D2 contract, nulls included`() {
        val client = mockk<OpaClient>()
        val request = slot<OpaRequest>()
        every { client.decision(capture(request)) } returns OpaResponse(OpaDecision(allow = false))

        opa(client).evaluate(query.copy(resource = null, plane = null))

        assertThat(request.captured.input).containsOnlyKeys("agent", "tool", "resource", "plane", "attributes")
        assertThat(request.captured.input["agent"]).isEqualTo("ui-assistant")
        assertThat(request.captured.input["resource"]).isNull()
        assertThat(request.captured.input["attributes"]).isEqualTo(mapOf("skill" to "read"))
    }

    @Test
    fun `a CRLF-bearing tool name cannot forge a log line and still fails closed`() {
        val client = mockk<OpaClient>()
        every { client.decision(any()) } throws IllegalStateException("boom")

        val decision = opa(client).evaluate(query.copy(tool = "get_account\nFATAL forged", agent = "a\rb"))

        assertThat(decision.allow).isFalse()
        assertThat(decision.pdpError).isTrue()
        // The subject is echoed verbatim in the decision; only the LOG rendering is sanitised.
        assertThat(decision.tool).isEqualTo("get_account\nFATAL forged")
    }
}
