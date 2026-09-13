// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The bank's policy over a classifier verdict on the OPERATOR plane. What is asserted is the pair
 * that decides whether this control is real: an unreachable classifier must be visible as
 * `unavailable` (never `safe`), and whether that blocks must follow the surface's declared posture
 * rather than a default nobody chose.
 */
class AgentContentSafetyGuardTest {

    private val identity = AgentIdentity(agentId = "ui-assistant", plane = "control", modelId = "test-chat-model")

    private class RecordingAudit : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events += event
        }
    }

    private class RecordingMetrics : ContentSafetyMetricsPort {
        data class Call(val model: String, val role: String, val decision: String, val blocked: Boolean)

        val calls = mutableListOf<Call>()
        override fun recordClassification(model: String, role: String, decision: String, blocked: Boolean) {
            calls += Call(model, role, decision, blocked)
        }
    }

    private fun port(verdict: ContentSafetyPort.SafetyVerdict) = object : ContentSafetyPort {
        override suspend fun classify(
            role: ContentSafetyPort.SafetyRole,
            text: String,
        ): ContentSafetyPort.SafetyVerdict = verdict
    }

    private fun guard(
        verdict: ContentSafetyPort.SafetyVerdict,
        failClosed: Boolean,
    ): Triple<AgentContentSafetyGuard, RecordingAudit, RecordingMetrics> {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        return Triple(AgentContentSafetyGuard(port(verdict), audit, metrics, failClosed), audit, metrics)
    }

    private val guardModel = "meta-llama/llama-guard-4-12b"

    private val safe = ContentSafetyPort.SafetyVerdict(ContentSafetyPort.Decision.SAFE, model = guardModel)

    private fun unsafe(vararg codes: String) =
        ContentSafetyPort.SafetyVerdict(ContentSafetyPort.Decision.UNSAFE, codes.toList(), guardModel)

    private val unavailable = ContentSafetyPort.SafetyVerdict(
        ContentSafetyPort.Decision.UNAVAILABLE,
        model = guardModel,
        reason = ContentSafetyPort.REASON_TRANSPORT,
    )

    @Test
    fun `a safe verdict passes and is not audited`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(safe, failClosed = false)

        assertThat(g.checkUserInput(identity, "which services are deployed?")).isFalse()
        // Safe is the overwhelming majority of traffic; auditing it would bury the interesting rows.
        assertThat(audit.events).isEmpty()
        assertThat(metrics.calls).isEmpty()
    }

    @Test
    fun `an unsafe verdict blocks and is audited as DENIED, naming the CLASSIFIER model`(): Unit = runBlocking {
        val (g, audit, _) = guard(unsafe("S2"), failClosed = false)

        assertThat(g.checkUserInput(identity, "…")).isTrue()
        val e = audit.events.single()
        assertThat(e.result).isEqualTo(AuditResult.DENIED)
        assertThat(e.operation).isEqualTo("agent.guardrail.content_safety")
        assertThat(e.actorType).isEqualTo("AI_AGENT")
        assertThat(e.actorId).isEqualTo("ui-assistant")
        // ADR-0031 D5, and enforced fleet-wide by AgentAuditAttributionTest: an AI-attributed event
        // names the model that acted — here the classifier, not the chat model on the identity.
        assertThat(e.payload["model_id"]).isEqualTo(guardModel)
        assertThat(e.payload["categories"]).isEqualTo("S2")
    }

    @Test
    fun `an unreachable classifier does not block on the operator plane, but is recorded`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unavailable, failClosed = false)

        assertThat(g.checkUserInput(identity, "which services are deployed?")).isFalse()
        assertThat(audit.events.single().result).isEqualTo(AuditResult.SUCCESS)
        assertThat(audit.events.single().payload["decision"]).isEqualTo("unavailable")
        assertThat(metrics.calls.single())
            .isEqualTo(RecordingMetrics.Call(guardModel, "user", "unavailable", false))
    }

    @Test
    fun `the same unreachable classifier blocks when the surface declares fail-closed`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unavailable, failClosed = true)

        assertThat(g.checkUserInput(identity, "…")).isTrue()
        assertThat(audit.events.single().result).isEqualTo(AuditResult.DENIED)
        assertThat(metrics.calls.single().blocked).isTrue()
    }

    @Test
    fun `assistant output is classified in the assistant role`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unsafe("S9"), failClosed = false)

        assertThat(g.checkAssistantOutput(identity, "…")).isTrue()
        assertThat(metrics.calls.single().role).isEqualTo("assistant")
        assertThat(audit.events.single().payload["role"]).isEqualTo("assistant")
    }

    @Test
    fun `blank text never reaches the classifier`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unsafe("S2"), failClosed = true)

        assertThat(g.checkAssistantOutput(identity, "  ")).isFalse()
        assertThat(audit.events).isEmpty()
        assertThat(metrics.calls).isEmpty()
    }

    @Test
    fun `the disabled port reports unavailable, so an unwired guardrail is never a clean bill`(): Unit = runBlocking {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        val g = AgentContentSafetyGuard(ContentSafetyPort.DISABLED, audit, metrics, failClosed = false)

        assertThat(g.checkUserInput(identity, "ahoj")).isFalse()
        assertThat(metrics.calls.single().decision).isEqualTo("unavailable")
    }
}
