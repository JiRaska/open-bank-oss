// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.domain.ToolCallResult
import com.openbank.agent.domain.ToolContent
import com.openbank.agent.domain.ToolDefinition
import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelResponse
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.domain.model.ToolInvocation
import com.openbank.agent.domain.policy.EnforcementMode
import com.openbank.agent.domain.policy.GateOutcome
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.libs.testing.trace.TraceContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * D7 (ADR-0031): every governed run must emit exactly one `agent.run` span carrying the agent
 * attributes, so traces in Tempo are queryable by agent / model / outcome. Uses a real SDK tracer
 * with an in-memory exporter — the production path, minus the OTLP wire.
 */
class AgentChatServiceTracingTest {

    private val mapper = jacksonObjectMapper()
    private val exported = mutableListOf<SpanData>()

    private val exporter = object : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            exported += spans
            return CompletableResultCode.ofSuccess()
        }
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
    private val tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()

    private fun service(gate: GateOutcome): AgentChatService {
        val registry = mockk<McpToolRegistry>().also {
            every { it.tools } returns listOf(ToolDefinition("get_account", "get account", emptyMap()))
            every { it.capabilityOf("get_account") } returns "query.ledger.readonly"
            every { it.call("get_account", any(), any()) } returns
                ToolCallResult(content = listOf(ToolContent(text = "{\"status\":\"ACTIVE\"}")))
        }
        return AgentChatService().apply {
            gateway = mockk { every { defaultModelId() } returns "mock-echo" }
            this.registry = registry
            policyGate = mockk { every { authorize(any(), any(), any(), any()) } returns gate }
            rateLimiter = mockk {
                every { checkRunsPerDay(any()) } returns null
                every { checkTokensPerRun(any(), any()) } returns null
            }
            charterRegistry = mockk { every { allowedCapabilities(any()) } returns emptySet() }
            runAuditor = mockk(relaxed = true)
            injectionGuard = mockk {
                coEvery { scanUserInput(any(), any()) } returns null
                coEvery { sanitizeToolResult(any(), any()) } answers { secondArg() }
                every { blocks() } returns true
            }
            contentSafety = mockk {
                coEvery { checkUserInput(any(), any()) } returns false
                coEvery { checkAssistantOutput(any(), any()) } returns false
            }
            killSwitch = mockk { every { haltReason(any()) } returns null }
            tracer = tracerProvider.get("test")
        }
    }

    private fun permittedGate() = GateOutcome(
        decision = PolicyDecision(
            allow = true,
            agent = "ui-assistant",
            tool = "query.ledger.readonly",
            resource = null,
            reason = "ok",
        ),
        mode = EnforcementMode.ADVISORY,
        proceed = true,
    )

    private fun toolUseThenEnd(): List<ModelResponse> {
        val args = mapper.createObjectNode().put("accountId", "44444444-4444-4444-4444-444444444444")
        return listOf(
            ModelResponse(
                toolInvocations = listOf(ToolInvocation("1", "get_account", args)),
                stopReason = StopReason.TOOL_USE,
                modelId = "mock-echo",
            ),
            ModelResponse(content = "Active.", stopReason = StopReason.END, modelId = "mock-echo"),
        )
    }

    private fun attrs(span: SpanData) = span.attributes.asMap().mapKeys { it.key.key }

    @Test
    fun `a run emits exactly one agent_run span with the agent attributes`() {
        runBlocking {
            val svc = service(permittedGate())
            coEvery { svc.gateway.complete(any(), any(), any(), any()) } returnsMany toolUseThenEnd()

            svc.chat(listOf(ChatMessage(ChatRole.USER, "show account")), null, "/accounts")

            assertThat(exported).singleElement()
            val span = exported.single()
            assertThat(span.name).isEqualTo("agent.run")
            val a = attrs(span)
            assertThat(a["openbank.agent.id"]).isEqualTo("ui-assistant")
            assertThat(a["openbank.agent.plane"]).isEqualTo("control")
            assertThat(a["openbank.agent.trigger"]).isEqualTo("chat")
            assertThat(a["openbank.agent.result"]).isEqualTo("SUCCESS")
            assertThat(a["openbank.agent.tool_calls"]).isEqualTo(1L)
            assertThat(a["openbank.agent.is_proposal"]).isEqualTo(false)
            assertThat(a["openbank.agent.model_id"]).isEqualTo("mock-echo")
            TraceContract.from(exported)
                .requiresSpan("agent.run")
                .requiresAttribute("agent.run", "openbank.agent.result")
                .hasNoErrorSpan()
                .verifiedAs("agent-run")
        }
    }

    @Test
    fun `a halted run still emits a span tagged with the deny detail`() {
        runBlocking {
            val svc = service(permittedGate()).apply {
                killSwitch = mockk { every { haltReason(any()) } returns "halted: maintenance" }
            }

            svc.chat(listOf(ChatMessage(ChatRole.USER, "balance?")), null, null)

            val span = exported.single()
            val a = attrs(span)
            assertThat(a["openbank.agent.result"]).isEqualTo("DENIED")
            assertThat(a["openbank.agent.detail"]).isEqualTo("kill_switch")
            assertThat(a["openbank.agent.tool_calls"]).isEqualTo(0L)
        }
    }
}
