// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.DecideProposalUseCase
import com.openbank.agent.application.port.`in`.ProposalQueries
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The HITL approval-queue API's own logic: which query the `state` parameter selects, how a
 * blank agent filter is normalised, and the status code each lifecycle failure maps to. The
 * segregation-of-duties rules themselves live in ProposalService — here the contract is that a
 * rejected decision surfaces as 409, never as a 500.
 */
class ProposalResourceTest {

    private val queries = mockk<ProposalQueries>()
    private val decisions = mockk<DecideProposalUseCase>()
    private val resource = ProposalResource().also {
        it.queries = queries
        it.decisions = decisions
    }

    private val id = UUID.randomUUID()
    private val row = AgentProposal(
        id = id,
        title = "raise the cap",
        rationale = "r",
        suggestedAction = "a",
        proposedBy = "ui-assistant",
        proposedAt = Instant.parse("2026-01-02T03:04:05Z"),
        state = ProposalState.PROPOSED,
        decidedBy = null,
        decidedAt = null,
        decisionReason = null,
        modelId = "llama-3.3",
        correlationId = "corr",
        metadata = mapOf("context_hash" to "abc"),
    )

    @Test
    fun `the default state lists only pending proposals`() {
        every { queries.listPending(null) } returns listOf(row)

        val dtos = resource.list("pending", null)

        assertThat(dtos).singleElement().satisfies({
            assertThat(it.id).isEqualTo(id.toString())
            assertThat(it.state).isEqualTo("PROPOSED")
            assertThat(it.proposedAt).isEqualTo(row.proposedAt)
            assertThat(it.modelId).isEqualTo("llama-3.3")
            assertThat(it.metadata).containsEntry("context_hash", "abc")
        })
        verify(exactly = 0) { queries.listAll(any(), any()) }
    }

    @Test
    fun `state=all switches to the capped full listing, case-insensitively`() {
        every { queries.listAll(100, null) } returns emptyList()

        assertThat(resource.list("ALL", null)).isEmpty()

        verify { queries.listAll(100, null) }
    }

    @Test
    fun `an unrecognised state falls back to pending rather than listing everything`() {
        every { queries.listPending(null) } returns emptyList()

        resource.list("archived", null)

        verify { queries.listPending(null) }
        verify(exactly = 0) { queries.listAll(any(), any()) }
    }

    @Test
    fun `a blank agent filter is normalised to no filter, a real one is trimmed`() {
        every { queries.listPending(null) } returns emptyList()
        every { queries.listPending("ui-assistant") } returns emptyList()

        resource.list("pending", "   ")
        resource.list("pending", "  ui-assistant ")

        verify { queries.listPending(null) }
        verify { queries.listPending("ui-assistant") }
    }

    @Test
    fun `a non-UUID id is a 400 and never reaches the use case`() {
        val response = resource.decide("not-a-uuid", ProposalResource.DecisionRequest(true, "bob"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity).isEqualTo(mapOf("error" to "invalid id"))
        verify(exactly = 0) { decisions.decide(any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown proposal is a 404`() {
        every { decisions.decide(id, true, "bob", null) } returns null

        val response = resource.decide(id.toString(), ProposalResource.DecisionRequest(true, "bob"))

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity).isEqualTo(mapOf("error" to "proposal not found"))
    }

    @Test
    fun `a rejected decision - self-approval or double decision - is a 409 carrying the reason`() {
        every { decisions.decide(id, true, "ui-assistant", null) } throws
            IllegalArgumentException("Segregation of duties: the approver must differ from the author")

        val response = resource.decide(id.toString(), ProposalResource.DecisionRequest(true, "ui-assistant"))

        assertThat(response.status).isEqualTo(409)
        assertThat(response.entity.toString()).contains("Segregation of duties")
    }

    @Test
    fun `a successful decision returns 200 with the decided DTO`() {
        val decided = row.copy(
            state = ProposalState.REJECTED,
            decidedBy = "bob",
            decidedAt = Instant.parse("2026-01-03T00:00:00Z"),
            decisionReason = "no",
        )
        every { decisions.decide(id, false, "bob", "no") } returns decided

        val response = resource.decide(id.toString(), ProposalResource.DecisionRequest(false, "bob", "no"))

        assertThat(response.status).isEqualTo(200)
        val dto = response.entity as ProposalResource.ProposalDto
        assertThat(dto.state).isEqualTo("REJECTED")
        assertThat(dto.decidedBy).isEqualTo("bob")
        assertThat(dto.decisionReason).isEqualTo("no")
    }
}
