// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.DecideProposalUseCase
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.Instant
import java.util.UUID

class ProposalResourceTest {

    private val proposalId = UUID.fromString("70d90e7f-f0aa-4c27-b812-6429159f5425")

    private fun resource(principal: String?): Pair<ProposalResource, DecideProposalUseCase> {
        val decisions = mockk<DecideProposalUseCase>()
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns principal?.let { Principal { it } }
        return ProposalResource().also {
            it.decisions = decisions
            it.identity = identity
        } to decisions
    }

    @Test
    fun `invalid id is rejected before attempting a decision`() {
        val (resource, decisions) = resource("reviewer")
        val response = resource.decide("not-a-uuid", ProposalResource.DecisionRequest(true, "forged"))

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { decisions.decide(any(), any(), any(), any()) }
    }

    @Test
    fun `missing or blank principal cannot be replaced by request actor`() {
        for (principal in listOf<String?>(null, " ")) {
            val (resource, decisions) = resource(principal)
            val response = resource.decide(
                proposalId.toString(),
                ProposalResource.DecisionRequest(true, "forged"),
            )

            assertThat(response.status).isEqualTo(403)
            verify(exactly = 0) { decisions.decide(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `not found decision uses authenticated actor and preserves reason`() {
        val (resource, decisions) = resource("reviewer")
        every { decisions.decide(proposalId, false, "reviewer", "insufficient evidence") } returns null

        val response = resource.decide(
            proposalId.toString(),
            ProposalResource.DecisionRequest(false, "forged", "insufficient evidence"),
        )

        assertThat(response.status).isEqualTo(404)
        verify(exactly = 1) { decisions.decide(proposalId, false, "reviewer", "insufficient evidence") }
    }

    @Test
    fun `successful decision returns the persisted audit identity`() {
        val (resource, decisions) = resource("reviewer")
        val decidedAt = Instant.parse("2026-09-28T12:00:00Z")
        val updated = AgentProposal(
            id = proposalId,
            title = "proposal",
            rationale = "rationale",
            suggestedAction = "action",
            proposedBy = "maker-agent",
            proposedAt = decidedAt.minusSeconds(60),
            state = ProposalState.APPROVED,
            decidedBy = "reviewer",
            decidedAt = decidedAt,
            decisionReason = "verified",
            modelId = null,
            correlationId = null,
        )
        every { decisions.decide(proposalId, true, "reviewer", "verified") } returns updated

        val response = resource.decide(
            proposalId.toString(),
            ProposalResource.DecisionRequest(true, "forged", "verified"),
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isInstanceOf(ProposalResource.ProposalDto::class.java)
        val dto = response.entity as ProposalResource.ProposalDto
        assertThat(dto.proposedBy).isEqualTo("maker-agent")
        assertThat(dto.decidedBy).isEqualTo("reviewer")
        assertThat(dto.decidedAt).isEqualTo(decidedAt)
    }

    @Test
    fun `separation of duties rejection remains a conflict`() {
        val (resource, decisions) = resource("maker")
        every { decisions.decide(proposalId, true, "maker", null) } throws IllegalArgumentException("self approval")

        val response = resource.decide(
            proposalId.toString(),
            ProposalResource.DecisionRequest(true, "someone-else"),
        )

        assertThat(response.status).isEqualTo(409)
        verify(exactly = 1) { decisions.decide(proposalId, true, "maker", null) }
    }
}
