// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.`in`.DecideProposalUseCase
import com.openbank.agent.application.port.`in`.ProposalQueries
import com.openbank.agent.application.port.out.AgentProposalRepository
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.util.UUID

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
 * Storage is behind [AgentProposalRepository] (ADR-0002 hexagonal): the rules live here, the SQL
 * lives in the adapter. Callers depend on the narrowest inbound port they need — the MCP tool path
 * only ever sees [CreateProposalUseCase], so the reasoning loop cannot decide its own proposal.
 */
@ApplicationScoped
class ProposalService(
    private val repository: AgentProposalRepository,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) : CreateProposalUseCase,
    ProposalQueries,
    DecideProposalUseCase {

    override fun create(
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
        repository.insert(row)
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
                        // ADR-0031 D5 (#3667): ALWAYS present. A conditional put makes an
                        // unattributed proposal indistinguishable from one nobody looked at;
                        // "unknown" is evidence, an absent key is a gap in the evidence chain.
                        put("model_id", modelId ?: CharterRegistry.UNKNOWN_MODEL)
                        correlationId?.let { put("correlation_id", it) }
                    },
                ),
            )
        }
        return row
    }

    /** [agentId] filters to one agent's proposals (matches proposed_by) — the /iaops/agents/<id> drill-down. */
    override fun listPending(agentId: String?): List<AgentProposal> = repository.listPending(agentId)

    override fun listAll(limit: Int, agentId: String?): List<AgentProposal> = repository.listAll(limit, agentId)

    override fun get(id: UUID): AgentProposal? = repository.findById(id)

    /**
     * Approve or reject. Fails closed on a double-decision and on self-approval (segregation of
     * duties). The proposal has NO side effect on approval — the agent never executes; approval is
     * the human's recorded sign-off (the operator then acts), ADR-0031 D4. The state guard is
     * pushed into the repository's conditional update, so two concurrent decisions can't both win.
     */
    override fun decide(id: UUID, approve: Boolean, decidedBy: String, reason: String?): AgentProposal? {
        val current = repository.findById(id) ?: return null
        require(current.state == ProposalState.PROPOSED) { "Proposal already ${current.state}" }
        require(decidedBy.isNotBlank() && decidedBy != current.proposedBy) {
            "Segregation of duties: the approver must differ from the author"
        }
        val newState = if (approve) ProposalState.APPROVED else ProposalState.REJECTED
        val decidedAt = clock.instant()
        val won = repository.compareAndSetDecision(id, newState, decidedBy, decidedAt, reason)
        if (!won) throw IllegalArgumentException("Proposal already decided")
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
}
