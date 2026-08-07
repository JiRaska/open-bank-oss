// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.out.PolicyDecisionPoint
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.agent.domain.policy.EnforcementMode
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.domain.policy.PolicyQuery
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentPolicyGateTest {

    private lateinit var pdp: PolicyDecisionPoint
    private lateinit var auditPublisher: AuditEventPublisher
    private lateinit var gate: AgentPolicyGate

    private val agent = AgentIdentity(agentId = "compliance-officer", plane = "control", skill = null)

    @BeforeEach
    fun setUp() {
        pdp = mockk()
        auditPublisher = mockk()
        coEvery { auditPublisher.publish(any()) } returns Unit
        gate = AgentPolicyGate().apply {
            pdp = this@AgentPolicyGateTest.pdp
            auditPublisher = this@AgentPolicyGateTest.auditPublisher
            enforcementMode = "advisory"
            // ADR-0080: no in-process allow-list configured for these tests → the gate falls
            // through to the PDP exactly as before.
            charterRegistry = mockk { every { allowedCapabilities(any()) } returns emptySet() }
        }
    }

    @Test
    fun `no identity is denied by default without consulting the PDP`() {
        gate.enforcementMode = "block"

        val outcome = gate.authorize(
            identity = null,
            tool = "get_account",
            capability = "query.ledger.readonly",
            resource = "acc-1",
        )

        assertThat(outcome.decision.allow).isFalse()
        assertThat(outcome.decision.agent).isEqualTo("anonymous")
        assertThat(outcome.decision.reason).contains("no agent identity")
        assertThat(outcome.proceed).isFalse()
        coVerify(exactly = 0) { pdp.evaluate(any()) }
    }

    @Test
    fun `ADR-0080 block mode denies a capability outside the charter allow-list without the PDP`() {
        gate.enforcementMode = "block"
        gate.charterRegistry = mockk {
            every { allowedCapabilities("compliance-officer") } returns setOf("query.ledger.readonly", "draft.ticket")
        }

        val outcome = gate.authorize(
            agent,
            tool = "aml_list_cases",
            capability = "query.compliance.readonly",
            resource = null,
        )

        assertThat(outcome.decision.allow).isFalse()
        assertThat(outcome.decision.reason).contains("charter allow-list")
        assertThat(outcome.proceed).isFalse() // pdpError stays false → block actually blocks
        coVerify(exactly = 0) { pdp.evaluate(any()) } // denied locally, never reaches OPA
    }

    @Test
    fun `ADR-0080 a capability inside the charter allow-list falls through to the PDP`() {
        gate.enforcementMode = "advisory"
        gate.charterRegistry = mockk {
            every { allowedCapabilities("compliance-officer") } returns setOf("query.ledger.readonly")
        }
        coEvery { pdp.evaluate(any()) } returns PolicyDecision(
            allow = true,
            agent = "compliance-officer",
            tool = "query.ledger.readonly",
            resource = null,
            reason = "ok",
        )

        gate.authorize(agent, tool = "get_account", capability = "query.ledger.readonly", resource = "acc-1")

        coVerify(exactly = 1) { pdp.evaluate(any()) } // allow-listed → PDP consulted as before
    }

    @Test
    fun `an unmapped tool is denied by default without consulting the PDP`() {
        gate.enforcementMode = "block"

        val outcome = gate.authorize(agent, tool = "delete_everything", capability = null, resource = null)

        assertThat(outcome.decision.allow).isFalse()
        assertThat(outcome.decision.reason).contains("no governance capability mapping")
        assertThat(outcome.proceed).isFalse()
        coVerify(exactly = 0) { pdp.evaluate(any()) }

        val event = slot<AuditEvent>()
        coVerify { auditPublisher.publish(capture(event)) }
        assertThat(event.captured.payload["tool"]).isEqualTo("delete_everything")
        assertThat(event.captured.payload["policy_decision"]).isEqualTo("DENY")
    }

    @Test
    fun `advisory mode lets a denied call proceed but still audits the DENY`() {
        every3Deny()

        val outcome = gate.authorize(
            agent,
            tool = "get_account",
            capability = "query.ledger.readonly",
            resource = "acc-1",
        )

        assertThat(outcome.decision.allow).isFalse()
        assertThat(outcome.mode).isEqualTo(EnforcementMode.ADVISORY)
        assertThat(outcome.proceed).isTrue()

        val event = slot<AuditEvent>()
        coVerify { auditPublisher.publish(capture(event)) }
        assertThat(event.captured.actorType).isEqualTo("AI_AGENT")
        assertThat(event.captured.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.captured.payload["policy_decision"]).isEqualTo("DENY")
        assertThat(event.captured.payload["tool"]).isEqualTo("get_account")
        assertThat(event.captured.payload["capability"]).isEqualTo("query.ledger.readonly")
    }

    @Test
    fun `block mode stops a denied call`() {
        gate.enforcementMode = "block"
        every3Deny()

        val outcome = gate.authorize(
            agent,
            tool = "get_account",
            capability = "query.ledger.readonly",
            resource = "acc-1",
        )

        assertThat(outcome.proceed).isFalse()
        coVerify { auditPublisher.publish(any()) }
    }

    @Test
    fun `allowed call proceeds and audits SUCCESS with the query forwarded to the PDP`() {
        val query = slot<PolicyQuery>()
        coEvery { pdp.evaluate(capture(query)) } answers {
            val q = query.captured
            PolicyDecision(
                allow = true,
                agent = q.agent,
                tool = q.tool,
                resource = q.resource,
                reason = "allowed by charter",
            )
        }
        gate.enforcementMode = "block"

        val skilled = agent.copy(skill = "ship-check")
        val outcome = gate.authorize(skilled, tool = "run_skill", capability = "run.skill", resource = null)

        assertThat(outcome.proceed).isTrue()
        assertThat(query.captured.agent).isEqualTo("compliance-officer")
        assertThat(query.captured.tool).isEqualTo("run.skill")
        assertThat(query.captured.plane).isEqualTo("control")
        assertThat(query.captured.attributes["skill"]).isEqualTo("ship-check")

        val event = slot<AuditEvent>()
        coVerify { auditPublisher.publish(capture(event)) }
        assertThat(event.captured.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.captured.payload["policy_decision"]).isEqualTo("ALLOW")
    }

    @Test
    fun `unknown enforcement mode falls back to advisory`() {
        gate.enforcementMode = "nonsense"
        every3Deny()

        val outcome = gate.authorize(
            agent,
            tool = "get_account",
            capability = "query.ledger.readonly",
            resource = "acc-1",
        )

        assertThat(outcome.mode).isEqualTo(EnforcementMode.ADVISORY)
        assertThat(outcome.proceed).isTrue()
    }

    @Test
    fun `audit payload includes model_id from identity (ADR-0031 D5)`() {
        coEvery { pdp.evaluate(any()) } answers {
            val q = firstArg<PolicyQuery>()
            PolicyDecision(allow = true, agent = q.agent, tool = q.tool, resource = null, reason = "ok")
        }
        val agentWithModel =
            AgentIdentity(agentId = "compliance-officer", plane = "control", modelId = "llama-3.3-70b-versatile")

        val event = slot<AuditEvent>()
        gate.authorize(agentWithModel, tool = "get_account", capability = "query.ledger.readonly", resource = null)
        coVerify { auditPublisher.publish(capture(event)) }

        assertThat(event.captured.payload["model_id"]).isEqualTo("llama-3.3-70b-versatile")
    }

    @Test
    fun `audit payload carries unknown model_id when identity has no charter model`() {
        val event = slot<AuditEvent>()
        gate.authorize(null, tool = "get_account", capability = "query.ledger.readonly", resource = null)
        coVerify { auditPublisher.publish(capture(event)) }

        assertThat(event.captured.payload["model_id"]).isEqualTo("unknown")
    }

    private fun every3Deny() {
        coEvery { pdp.evaluate(any()) } answers {
            val q = firstArg<PolicyQuery>()
            PolicyDecision(
                allow = false,
                agent = q.agent,
                tool = q.tool,
                resource = q.resource,
                reason = "denied by charter",
            )
        }
    }
}
