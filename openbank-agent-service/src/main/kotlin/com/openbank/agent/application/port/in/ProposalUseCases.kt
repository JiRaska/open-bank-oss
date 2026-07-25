// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.`in`

import com.openbank.agent.domain.proposal.AgentProposal
import java.util.UUID

/**
 * Inbound port: an agent materialises a reviewable proposal (ADR-0031 D4).
 *
 * Deliberately split from [DecideProposalUseCase]: the MCP tool path (`draft_ticket`,
 * `flip_feature_flag`) is wired to *this* interface only, so the reasoning loop is structurally
 * incapable of approving its own proposal — segregation of duties expressed in the type system,
 * not only in a runtime `require`.
 */
interface CreateProposalUseCase {
    fun create(
        title: String,
        rationale: String,
        suggestedAction: String,
        proposedBy: String,
        modelId: String?,
        correlationId: String?,
    ): AgentProposal
}

/** Inbound port: read side of the HITL approval queue, for the admin-ui BFF and the oversight sweep. */
interface ProposalQueries {

    /** Proposals awaiting a human decision; [agentId] scopes to one agent's `proposed_by`. */
    fun listPending(agentId: String? = null): List<AgentProposal>

    fun listAll(limit: Int, agentId: String? = null): List<AgentProposal>

    fun get(id: UUID): AgentProposal?
}

/**
 * Inbound port: a human approves or rejects (ADR-0031 D4). Only the operator-authenticated REST
 * surface is wired to this — never the agent loop.
 */
interface DecideProposalUseCase {

    /**
     * Record [decidedBy]'s decision. Returns null when the proposal does not exist.
     *
     * @throws IllegalArgumentException on a double decision, or when [decidedBy] is the author
     *   (segregation of duties).
     */
    fun decide(id: UUID, approve: Boolean, decidedBy: String, reason: String?): AgentProposal?
}
