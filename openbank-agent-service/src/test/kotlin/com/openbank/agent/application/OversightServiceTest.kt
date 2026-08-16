// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OversightServiceTest {

    private fun pendingProposal(title: String) = AgentProposal(
        id = UUID.randomUUID(),
        title = title,
        rationale = "r",
        suggestedAction = "a",
        proposedBy = "compliance-officer",
        proposedAt = Instant.now(),
        state = ProposalState.PROPOSED,
        decidedBy = null,
        decidedAt = null,
        decisionReason = null,
        modelId = null,
        correlationId = null,
    )

    private fun outcome() = AgentChatService.ChatOutcome(
        reply = "All quiet.",
        model = "mock-echo",
        toolCalls = emptyList(),
        isProposal = false,
    )

    @Test
    fun `sweep runs under the compliance-officer identity and feeds pending titles into the prompt`() {
        runBlocking {
            val chat = mockk<AgentChatService>()
            coEvery { chat.run(any(), any(), any(), any(), any()) } returns outcome()
            val service = OversightService().apply {
                chatService = chat
                proposals = mockk {
                    every { listPending() } returns listOf(pendingProposal("Review screening SCR-1"))
                }
                injectionGuard = mockk { every { sanitizeInline(any(), any()) } answers { firstArg() } }
            }

            val out = service.sweep(trigger = "manual")

            assertThat(out.reply).isEqualTo("All quiet.")
            coVerify(exactly = 1) {
                chat.run(
                    identity = match { it.agentId == "compliance-officer" && it.plane == "control" },
                    systemPrompt = match { "Review screening SCR-1" in it && "draft_ticket" in it },
                    history = match { it.size == 1 },
                    modelId = null,
                    trigger = "manual",
                )
            }
        }
    }

    @Test
    fun `scheduled sweep is a no-op until oversight is enabled`(): Unit = runBlocking {
        val chat = mockk<AgentChatService>()
        val metrics = mockk<DomainMetrics>()
        val service = OversightService().apply {
            chatService = chat
            proposals = mockk()
            injectionGuard = mockk()
            enabled = false
            domainMetrics = metrics
        }

        service.registerLiveness(StartupEvent())
        service.scheduledSweep()

        coVerify(exactly = 0) { chat.run(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { metrics.registerWorkflowLiveness(any(), any()) }
    }

    @Test
    fun `scheduled sweep runs with the scheduled trigger when enabled`(): Unit = runBlocking {
        val chat = mockk<AgentChatService>()
        val metrics = mockk<DomainMetrics>()
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        coEvery { chat.run(any(), any(), any(), any(), any()) } returns outcome()
        val service = OversightService().apply {
            chatService = chat
            proposals = mockk { every { listPending() } returns emptyList() }
            injectionGuard = mockk { every { sanitizeInline(any(), any()) } answers { firstArg() } }
            enabled = true
            domainMetrics = metrics
        }
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness

        service.registerLiveness(StartupEvent())
        service.scheduledSweep()

        coVerify(exactly = 1) { chat.run(any(), any(), any(), any(), trigger = "scheduled") }
        verify(exactly = 1) { liveness.recordSuccess() }
    }
}
