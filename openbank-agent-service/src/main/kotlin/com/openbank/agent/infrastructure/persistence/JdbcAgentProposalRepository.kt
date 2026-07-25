// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.persistence

import com.openbank.agent.application.port.out.AgentProposalRepository
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import jakarta.enterprise.context.ApplicationScoped
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Plain Agroal JDBC adapter for [AgentProposalRepository] (`agent_proposal`, V1 migration).
 *
 * Deliberately NOT Hibernate/Panache: this service depends on openbank-libs, which ships Hibernate
 * *Reactive* Panache entities (outbox, four-eyes). Pulling in Hibernate ORM here makes the ORM
 * JpaJandexScavenger try to register those reactive entities and fail. The synchronous MCP `call()`
 * path also cannot drive reactive Panache — so sync, immediate-consistency CRUD it is.
 */
@ApplicationScoped
class JdbcAgentProposalRepository(private val dataSource: DataSource) : AgentProposalRepository {

    @Suppress("MagicNumber") // positional JDBC bind indexes
    override fun insert(proposal: AgentProposal) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO agent_proposal
                  (id, title, rationale, suggested_action, proposed_by, proposed_at, state, model_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, proposal.id)
                ps.setString(2, proposal.title)
                ps.setString(3, proposal.rationale)
                ps.setString(4, proposal.suggestedAction)
                ps.setString(5, proposal.proposedBy)
                ps.setTimestamp(6, Timestamp.from(proposal.proposedAt))
                ps.setString(7, proposal.state.name)
                ps.setString(8, proposal.modelId)
                ps.setString(9, proposal.correlationId)
                ps.executeUpdate()
            }
        }
    }

    override fun findById(id: UUID): AgentProposal? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT * FROM agent_proposal WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> rs.firstProposalOrNull() }
        }
    }

    override fun listPending(agentId: String?): List<AgentProposal> = query(
        "SELECT * FROM agent_proposal WHERE state = 'PROPOSED'" +
            (if (agentId != null) " AND proposed_by = ?" else "") +
            " ORDER BY proposed_at DESC",
        agentId,
    )

    override fun listAll(limit: Int, agentId: String?): List<AgentProposal> = query(
        "SELECT * FROM agent_proposal" +
            (if (agentId != null) " WHERE proposed_by = ?" else "") +
            " ORDER BY proposed_at DESC LIMIT ${limit.coerceIn(1, MAX_PAGE)}",
        agentId,
    )

    /**
     * The `WHERE … AND state = 'PROPOSED'` guard runs in the same statement as the update, so two
     * concurrent decisions can never both win — the loser sees 0 updated rows.
     */
    @Suppress("MagicNumber") // positional JDBC bind indexes
    override fun compareAndSetDecision(
        id: UUID,
        newState: ProposalState,
        decidedBy: String,
        decidedAt: Instant,
        reason: String?,
    ): Boolean {
        val updated = dataSource.connection.use { c ->
            c.prepareStatement(
                """
                UPDATE agent_proposal
                   SET state = ?, decided_by = ?, decided_at = ?, decision_reason = ?
                 WHERE id = ? AND state = 'PROPOSED'
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, newState.name)
                ps.setString(2, decidedBy)
                ps.setTimestamp(3, Timestamp.from(decidedAt))
                ps.setString(4, reason)
                ps.setObject(5, id)
                ps.executeUpdate()
            }
        }
        return updated > 0
    }

    /** [agentId] binds to the sole '?' placeholder in [sql] (proposed_by), if the caller included one. */
    private fun query(sql: String, agentId: String? = null): List<AgentProposal> = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            agentId?.let { ps.setString(1, it) }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toProposal()) }
            }
        }
    }

    private fun ResultSet.firstProposalOrNull(): AgentProposal? = if (next()) toProposal() else null

    private fun ResultSet.toProposal() = AgentProposal(
        id = getObject("id", UUID::class.java),
        title = getString("title"),
        rationale = getString("rationale"),
        suggestedAction = getString("suggested_action"),
        proposedBy = getString("proposed_by"),
        proposedAt = getTimestamp("proposed_at").toInstant(),
        state = ProposalState.valueOf(getString("state")),
        decidedBy = getString("decided_by"),
        decidedAt = getTimestamp("decided_at")?.toInstant(),
        decisionReason = getString("decision_reason"),
        modelId = getString("model_id"),
        correlationId = getString("correlation_id"),
    )

    private companion object {
        const val MAX_PAGE = 200
    }
}
