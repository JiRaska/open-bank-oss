// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.observability

import com.openbank.agent.application.ProposalService
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The pending-proposal backlog gauge (ADR-0077). Two failure shapes are worth a test: the gauge
 * must actually track the store (a gauge stuck at its seed reads as a healthy empty queue), and
 * the adapter must survive with no MeterRegistry on the classpath — the liveness beat still has
 * to be recorded, or the workflow reads as dead.
 */
class AgentMetricsAdapterTest {

    private val proposals = mockk<ProposalService>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val domainMetrics = mockk<DomainMetrics>().also {
        every { it.registerWorkflowLiveness(any(), any()) } returns liveness
    }

    private fun pending(n: Int): List<AgentProposal> = (1..n).map {
        AgentProposal(
            id = UUID.randomUUID(),
            title = "t$it",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "agent",
            proposedAt = Instant.EPOCH,
            state = ProposalState.PROPOSED,
            decidedBy = null,
            decidedAt = null,
            decisionReason = null,
            modelId = null,
            correlationId = null,
        )
    }

    private fun adapter(registry: SimpleMeterRegistry?) =
        AgentMetricsAdapter(proposals, registry).also {
            it.domainMetrics = domainMetrics
            it.register()
        }

    @Test
    fun `the gauge follows the backlog across refreshes, up and back down`() {
        val registry = SimpleMeterRegistry()
        val adapter = adapter(registry)
        val gauge = registry.find("openbank.agent.proposals.pending").tag("service", "agent").gauge()
        assertThat(gauge).isNotNull()
        assertThat(gauge!!.value()).isEqualTo(0.0)

        every { proposals.listPending(null) } returns pending(3)
        adapter.refresh()
        assertThat(gauge.value()).isEqualTo(3.0)

        every { proposals.listPending(null) } returns emptyList()
        adapter.refresh()
        assertThat(gauge.value()).isEqualTo(0.0)
    }

    @Test
    fun `every refresh records a liveness beat, so a stalled tick is visible`() {
        every { proposals.listPending(null) } returns emptyList()
        val adapter = adapter(SimpleMeterRegistry())

        adapter.refresh()
        adapter.refresh()

        verify(exactly = 2) { liveness.recordSuccess() }
    }

    @Test
    fun `with no MeterRegistry the adapter still registers liveness and refreshes without throwing`() {
        every { proposals.listPending(null) } returns pending(2)
        val adapter = adapter(null)

        adapter.refresh()

        verify { domainMetrics.registerWorkflowLiveness("agent-proposal-backlog-refresh", any()) }
        verify { liveness.recordSuccess() }
    }
}
