// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The run-level AI-attribution event (ADR-0031 D5) a DORA Art. 17 reconstruction starts from.
 * Two things must hold: the prompt is hashed and never carried verbatim, and the keys the
 * evidence chain depends on are unconditionally present.
 */
class AgentRunAuditorTest {

    private val events = mutableListOf<AuditEvent>()
    private val auditor = AgentRunAuditor().also {
        it.auditPublisher = mockk<AuditEventPublisher>().apply {
            coEvery { publish(capture(events)) } returns Unit
        }
    }

    private fun run(
        detail: String? = null,
        result: AuditResult = AuditResult.SUCCESS,
        toolCalls: List<AgentChatService.ToolCallRecord> = emptyList(),
    ) = AgentRunAuditor.AgentRun(
        identity = AgentIdentity(agentId = "ui-assistant", plane = "data"),
        trigger = "chat",
        modelId = "llama-3.3",
        promptHash = "deadbeef",
        toolCalls = toolCalls,
        totalTokens = 1234L,
        isProposal = false,
        result = result,
        detail = detail,
    )

    @Test
    fun `the run event is attributed to the agent identity, not to a human`(): Unit = runBlocking {
        auditor.runCompleted(run())

        val event = events.single()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.actorId).isEqualTo("ui-assistant")
        assertThat(event.resourceId).isEqualTo("ui-assistant")
        assertThat(event.operation).isEqualTo("agent.run")
        assertThat(event.payload).containsEntry("plane", "data")
        assertThat(event.payload).containsEntry("trigger", "chat")
        assertThat(event.payload).containsEntry("model_id", "llama-3.3")
        assertThat(event.payload).containsEntry("prompt_hash", "deadbeef")
        assertThat(event.payload).containsEntry("total_tokens", 1234L)
        assertThat(event.payload).containsEntry("is_proposal", false)
    }

    @Test
    fun `detail is omitted when absent and carried through on a failed run`(): Unit = runBlocking {
        auditor.runCompleted(run(detail = null))
        assertThat(events.single().payload).doesNotContainKey("detail")

        events.clear()
        auditor.runCompleted(run(detail = "model timeout", result = AuditResult.FAILURE))
        assertThat(events.single().result).isEqualTo(AuditResult.FAILURE)
        assertThat(events.single().payload).containsEntry("detail", "model timeout")
    }

    @Test
    fun `each tool call is reduced to its name and policy outcome, dropping arguments`(): Unit = runBlocking {
        auditor.runCompleted(
            run(
                toolCalls = listOf(
                    AgentChatService.ToolCallRecord("get_account", allowed = true, resultPreview = "{}"),
                    AgentChatService.ToolCallRecord("aml_list_cases", allowed = false, resultPreview = ""),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val calls = events.single().payload["tool_calls"] as List<Map<String, Any?>>
        assertThat(calls).containsExactly(
            mapOf("tool" to "get_account", "allowed" to true),
            mapOf("tool" to "aml_list_cases", "allowed" to false),
        )
    }

    @Test
    fun `promptHash is a stable SHA-256 over role and content, sensitive to either`() {
        val base = listOf(ChatMessage(ChatRole.USER, "what is my balance"))

        val hash = AgentRunAuditor.promptHash(base)

        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(hash).isEqualTo(AgentRunAuditor.promptHash(base))
        assertThat(hash).isNotEqualTo(
            AgentRunAuditor.promptHash(listOf(ChatMessage(ChatRole.SYSTEM, "what is my balance"))),
        )
        assertThat(hash).isNotEqualTo(
            AgentRunAuditor.promptHash(listOf(ChatMessage(ChatRole.USER, "what is my balance?"))),
        )
        // The raw prompt must not survive into the hash in any recoverable form.
        assertThat(hash).doesNotContain("balance")
    }

    @Test
    fun `promptHash joins turns, so a split prompt differs from a concatenated one`() {
        val split = AgentRunAuditor.promptHash(
            listOf(ChatMessage(ChatRole.USER, "a"), ChatMessage(ChatRole.USER, "b")),
        )
        val joined = AgentRunAuditor.promptHash(listOf(ChatMessage(ChatRole.USER, "ab")))

        assertThat(split).isNotEqualTo(joined)
        assertThat(AgentRunAuditor.promptHash(emptyList())).matches("[0-9a-f]{64}")
    }
}
