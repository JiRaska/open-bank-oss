// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.observability.WorkflowRunMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
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
        verify(exactly = 0) { metrics.registerWorkflowRun(any(), any()) }
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
        every { metrics.registerWorkflowRun(any(), any()) } returns mockk(relaxed = true)

        service.registerLiveness(StartupEvent())
        service.scheduledSweep()

        coVerify(exactly = 1) { chat.run(any(), any(), any(), any(), trigger = "scheduled") }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    /**
     * The #6169 signal, proved by EFFECT against a real Micrometer registry rather than by verifying
     * a mock call: the sweep's own timer is what replaced `traces_spanmetrics_latency_bucket`, whose
     * top finite bucket is 5s and which therefore reports 5.00 for every run this job has ever had.
     *
     * Note what is asserted about the value — that a sample EXISTS and carries a duration, not that
     * it equals a number. Pinning wall-clock in a unit test is how a test starts failing on a busy
     * machine; what must not regress is the sample being recorded at all.
     */
    @Test
    fun `a scheduled sweep records its own run duration, tagged success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val chat = mockk<AgentChatService>()
        coEvery { chat.run(any(), any(), any(), any(), any()) } returns outcome()
        val service = OversightService().apply {
            chatService = chat
            proposals = mockk { every { listPending() } returns emptyList() }
            injectionGuard = mockk { every { sanitizeInline(any(), any()) } answers { firstArg() } }
            enabled = true
            domainMetrics = domainMetricsOn(registry)
        }

        service.registerLiveness(StartupEvent())

        // t=0, cold pod: registered, never run. The budget is published, the run count is zero, and
        // the alert's denominator (increase of _count) is therefore empty rather than a value that
        // reads as "instant" or as a breach. This is the state WorkflowLivenessStale failed to
        // re-derive and paid for with a false alert 15 minutes after every deploy (#2239/#4208).
        assertThat(
            registry.find(WorkflowRunMetrics.RUN_BUDGET_SECONDS)
                .tag(WorkflowRunMetrics.WORKFLOW_TAG, "agent-oversight-sweep").gauge()!!.value(),
        ).isEqualTo(Duration.ofMinutes(5).toSeconds().toDouble())
        assertThat(successTimerCount(registry)).isEqualTo(0L)

        service.scheduledSweep()

        assertThat(successTimerCount(registry))
            .describedAs("one completed sweep must contribute exactly one duration sample")
            .isEqualTo(1L)
        assertThat(
            registry.find(WorkflowRunMetrics.RUN_DURATION)
                .tag(WorkflowRunMetrics.OUTCOME_TAG, WorkflowRunMetrics.OUTCOME_SUCCESS)
                .timer()!!.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS),
        ).isGreaterThan(0.0)
    }

    @Test
    fun `a sweep that throws is still timed, under outcome failure`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val chat = mockk<AgentChatService>()
        coEvery { chat.run(any(), any(), any(), any(), any()) } throws IllegalStateException("gateway down")
        val service = OversightService().apply {
            chatService = chat
            proposals = mockk { every { listPending() } returns emptyList() }
            injectionGuard = mockk { every { sanitizeInline(any(), any()) } answers { firstArg() } }
            enabled = true
            domainMetrics = domainMetricsOn(registry)
        }
        service.registerLiveness(StartupEvent())

        runCatching { service.scheduledSweep() }

        // A job that fails SLOWLY is exactly what a duration alert is for, so the failing run has to
        // land in the histogram too — and under its own tag, so a fail-fast run cannot quietly pull
        // the mean down and mask a slow success.
        assertThat(successTimerCount(registry)).isEqualTo(0L)
        assertThat(
            registry.find(WorkflowRunMetrics.RUN_DURATION)
                .tag(WorkflowRunMetrics.OUTCOME_TAG, WorkflowRunMetrics.OUTCOME_FAILURE).timer()!!.count(),
        ).isEqualTo(1L)
    }

    private fun successTimerCount(registry: MeterRegistry): Long = registry.find(WorkflowRunMetrics.RUN_DURATION)
        .tags(
            WorkflowRunMetrics.WORKFLOW_TAG,
            "agent-oversight-sweep",
            WorkflowRunMetrics.OUTCOME_TAG,
            WorkflowRunMetrics.OUTCOME_SUCCESS,
        ).timer()!!.count()

    private fun domainMetricsOn(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }
}
