// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.application

import com.openbank.agent.infrastructure.persistence.AgentProposal
import com.openbank.agent.infrastructure.persistence.ProposalState
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * The agent's proposal lifecycle (ADR-0031 D4: agents propose, governance disposes). A control
 * agent materialises a reviewable proposal; a *different* human approves or rejects it before it
 * has any effect. Segregation of duties is enforced here (approver_must_differ_from author), the
 * same maker-checker rule the rest of the platform uses (openbank-libs MakerChecker).
 *
 * Both lifecycle transitions are audited (ADR-0031 D5): creation under the proposing agent
 * (`AI_AGENT`), the decision under the deciding human (`HUMAN`, with the recorded reason) — the
 * `human_approver` + `reason` half of the AI-attribution evidence chain. Audited here, at the
 * lifecycle owner, so every creator/decider path (MCP tool, REST, scheduled run) is covered.
 *
 * Plain Agroal JDBC, not Hibernate: the MCP `call()` path is synchronous (worker thread) and this
 * service depends on openbank-libs' reactive Panache entities — see AgentProposal for the full why.
 */
@ApplicationScoped
class ProposalService(
    private val dataSource: DataSource,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) {

    fun create(
        title: String,
        rationale: String,
        suggestedAction: String,
        proposedBy: String,
        modelId: String?,
        correlationId: String?,
    ): AgentProposal {
        val row = AgentProposal(
            id = UUID.randomUUID(),
            title = title,
            rationale = rationale,
            suggestedAction = suggestedAction,
            proposedBy = proposedBy,
            proposedAt = clock.instant(),
            state = ProposalState.PROPOSED,
            decidedBy = null,
            decidedAt = null,
            decisionReason = null,
            modelId = modelId,
            correlationId = correlationId,
        )
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO agent_proposal
                  (id, title, rationale, suggested_action, proposed_by, proposed_at, state, model_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, row.id)
                ps.setString(2, row.title)
                ps.setString(3, row.rationale)
                ps.setString(4, row.suggestedAction)
                ps.setString(5, row.proposedBy)
                ps.setTimestamp(6, Timestamp.from(row.proposedAt))
                ps.setString(7, row.state.name)
                ps.setString(8, row.modelId)
                ps.setString(9, row.correlationId)
                ps.executeUpdate()
            }
        }
        runBlocking {
            auditPublisher.publish(
                AuditEvent(
                    actorId = proposedBy,
                    actorType = "AI_AGENT",
                    operation = "agent.proposal.created",
                    resourceType = "agent.proposal",
                    resourceId = row.id.toString(),
                    result = AuditResult.SUCCESS,
                    // Title only — rationale may quote data the agent read (possibly PII).
                    payload = buildMap {
                        put("title", title)
                        put("state", row.state.name)
                        modelId?.let { put("model_id", it) }
                        correlationId?.let { put("correlation_id", it) }
                    },
                ),
            )
        }
        return row
    }

    fun listPending(): List<AgentProposal> =
        query("SELECT * FROM agent_proposal WHERE state = 'PROPOSED' ORDER BY proposed_at DESC")

    fun listAll(limit: Int): List<AgentProposal> =
        query("SELECT * FROM agent_proposal ORDER BY proposed_at DESC LIMIT ${limit.coerceIn(1, 200)}")

    fun get(id: UUID): AgentProposal? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT * FROM agent_proposal WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toProposal() else null }
        }
    }

    /**
     * Approve or reject. Fails closed on a double-decision and on self-approval (segregation of
     * duties). The proposal has NO side effect on approval — the agent never executes; approval is
     * the human's recorded sign-off (the operator then acts), ADR-0031 D4. The state guard runs in
     * the same transaction as the update (WHERE state = 'PROPOSED') so two concurrent decisions
     * can't both win.
     */
    fun decide(id: UUID, approve: Boolean, decidedBy: String, reason: String?): AgentProposal? {
        val current = get(id) ?: return null
        require(current.state == ProposalState.PROPOSED) { "Proposal already ${current.state}" }
        require(decidedBy.isNotBlank() && decidedBy != current.proposedBy) {
            "Segregation of duties: the approver must differ from the author"
        }
        val newState = if (approve) ProposalState.APPROVED else ProposalState.REJECTED
        val decidedAt = clock.instant()
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
        if (updated == 0) throw IllegalArgumentException("Proposal already decided")
        runBlocking {
            auditPublisher.publish(
                AuditEvent(
                    actorId = decidedBy,
                    actorType = "HUMAN",
                    operation = "agent.proposal.decided",
                    resourceType = "agent.proposal",
                    resourceId = id.toString(),
                    result = AuditResult.SUCCESS,
                    payload = buildMap {
                        put("decision", newState.name)
                        put("proposed_by", current.proposedBy)
                        put("title", current.title)
                        reason?.let { put("reason", it) }
                    },
                ),
            )
        }
        return current.copy(state = newState, decidedBy = decidedBy, decidedAt = decidedAt, decisionReason = reason)
    }

    private fun query(sql: String): List<AgentProposal> = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toProposal()) }
            }
        }
    }

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
}
