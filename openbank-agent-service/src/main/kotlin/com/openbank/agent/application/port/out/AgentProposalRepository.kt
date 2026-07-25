// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import java.time.Instant
import java.util.UUID

/**
 * Outbound port: system of record for the HITL approval queue (ADR-0002 hexagonal, ADR-0031 D4).
 *
 * Implemented by
 * [com.openbank.agent.infrastructure.persistence.JdbcAgentProposalRepository] — plain Agroal JDBC,
 * not Panache: this service depends on openbank-libs' Hibernate *Reactive* entities, which the
 * synchronous MCP `call()` path cannot drive.
 *
 * Methods are blocking on purpose (every caller is on a `@Blocking` worker thread); the port exists
 * so the lifecycle rules — segregation of duties, audit, no-double-decision — live in
 * [com.openbank.agent.application.ProposalService] rather than next to the SQL.
 */
interface AgentProposalRepository {

    /** Persist a freshly created PROPOSED proposal. */
    fun insert(proposal: AgentProposal)

    fun findById(id: UUID): AgentProposal?

    /** Proposals still awaiting a human decision, newest first; [agentId] filters on `proposed_by`. */
    fun listPending(agentId: String?): List<AgentProposal>

    /** Every proposal regardless of state, newest first, capped at [limit]. */
    fun listAll(limit: Int, agentId: String?): List<AgentProposal>

    /**
     * Atomically move [id] from PROPOSED to [newState]. Returns `false` when the row was no longer
     * PROPOSED, i.e. a concurrent decision won the race — the guard and the write MUST be one
     * statement so two deciders can never both succeed (the reason this is a port method and not a
     * read-modify-write in the service).
     */
    fun compareAndSetDecision(
        id: UUID,
        newState: ProposalState,
        decidedBy: String,
        decidedAt: Instant,
        reason: String?,
    ): Boolean
}
