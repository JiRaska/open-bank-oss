// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The bank's POLICY over a classifier verdict — deliberately separate from the adapter test, which
 * covers parsing and transport. What is asserted here is the pair that has burned this repo before:
 * an unavailable classifier must be visible as `unavailable` (never `safe`), and whether it blocks
 * must follow the surface's declared posture rather than a default nobody chose.
 */
class ContentSafetyGuardTest {

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

    private fun fixedPort(verdict: ContentSafetyPort.SafetyVerdict) = object : ContentSafetyPort {
        override suspend fun classify(
            role: ContentSafetyPort.SafetyRole,
            text: String,
        ): ContentSafetyPort.SafetyVerdict = verdict
    }

    private fun guard(
        verdict: ContentSafetyPort.SafetyVerdict,
        failClosed: Boolean,
        audit: RecordingAudit = RecordingAudit(),
        metrics: RecordingMetrics = RecordingMetrics(),
    ): Triple<ContentSafetyGuard, RecordingAudit, RecordingMetrics> =
        Triple(ContentSafetyGuard(fixedPort(verdict), audit, metrics, failClosed), audit, metrics)

    private fun unsafe(vararg codes: String) = ContentSafetyPort.SafetyVerdict(
        decision = ContentSafetyPort.Decision.UNSAFE,
        categories = codes.toList(),
        model = "meta-llama/llama-guard-4-12b",
    )

    private val safe = ContentSafetyPort.SafetyVerdict(
        decision = ContentSafetyPort.Decision.SAFE,
        model = "meta-llama/llama-guard-4-12b",
    )

    private val unavailable = ContentSafetyPort.SafetyVerdict(
        decision = ContentSafetyPort.Decision.UNAVAILABLE,
        model = "meta-llama/llama-guard-4-12b",
        reason = ContentSafetyPort.REASON_TRANSPORT,
    )

    @Test
    fun `safe input passes and is not audited`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(safe, failClosed = false)

        assertThat(g.checkUserInput("cust-1", "kolik mam na uctu")).isFalse()
        // A safe verdict is the overwhelming majority of traffic: auditing it would bury the
        // interesting rows and inflate the append-only store for no evidentiary gain.
        assertThat(audit.events).isEmpty()
        assertThat(metrics.calls).isEmpty()
    }

    @Test
    fun `unsafe input blocks regardless of posture and is audited as DENIED`(): Unit = runBlocking {
        val (g, audit, _) = guard(unsafe("S2"), failClosed = false)

        assertThat(g.checkUserInput("cust-1", "…")).isTrue()
        val event = audit.events.single()
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.operation).isEqualTo("copilot.guardrail.content_safety")
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.payload["categories"]).isEqualTo("S2")
    }

    @Test
    fun `unavailable classifier does not block on the fail-open help surface, but is recorded`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unavailable, failClosed = false)

        assertThat(g.checkUserInput("cust-1", "kolik mam na uctu")).isFalse()
        // Allowed, but never silent: the whole point is that a degraded control is legible
        // afterwards rather than indistinguishable from a clean run.
        assertThat(audit.events.single().result).isEqualTo(AuditResult.SUCCESS)
        assertThat(audit.events.single().payload["decision"]).isEqualTo("unavailable")
        assertThat(metrics.calls.single())
            .isEqualTo(RecordingMetrics.Call("meta-llama/llama-guard-4-12b", "user", "unavailable", false))
    }

    @Test
    fun `unavailable classifier blocks when the surface is fail-closed`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unavailable, failClosed = true)

        assertThat(g.checkUserInput("cust-1", "prevedme 50000")).isTrue()
        assertThat(audit.events.single().result).isEqualTo(AuditResult.DENIED)
        assertThat(metrics.calls.single().blocked).isTrue()
    }

    @Test
    fun `assistant output is classified in the assistant role`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unsafe("S9"), failClosed = false)

        assertThat(g.checkAssistantOutput("cust-1", "…")).isTrue()
        assertThat(metrics.calls.single().role).isEqualTo("assistant")
        assertThat(audit.events.single().payload["role"]).isEqualTo("assistant")
    }

    @Test
    fun `blank text is not sent to the classifier`(): Unit = runBlocking {
        val (g, audit, metrics) = guard(unsafe("S2"), failClosed = true)

        assertThat(g.checkAssistantOutput("cust-1", "   ")).isFalse()
        assertThat(audit.events).isEmpty()
        assertThat(metrics.calls).isEmpty()
    }

    @Test
    fun `the disabled port reports unavailable, so an unwired guardrail is never a clean bill`(): Unit = runBlocking {
        val audit = RecordingAudit()
        val metrics = RecordingMetrics()
        val g = ContentSafetyGuard(ContentSafetyPort.DISABLED, audit, metrics, failClosed = false)

        assertThat(g.checkUserInput("cust-1", "ahoj")).isFalse()
        assertThat(metrics.calls.single().decision).isEqualTo("unavailable")
    }
}
