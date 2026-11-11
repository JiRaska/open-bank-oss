// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.report

import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.application.port.out.DispositionProposalRepository
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.report.CaseDisposition
import com.openbank.aml.domain.report.DispositionProposal
import com.openbank.aml.domain.report.DispositionRequest
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

class DispositionProposalNotFoundException(id: UUID) : ResourceNotFoundException("disposition proposal not found: $id")

class DispositionConflictException(message: String) : ResourceConflictException(message)

/**
 * Closing a monitoring case takes two different people (ADR-0371 §5).
 *
 * One proposes a disposition with a structured outcome; a second approves or rejects it. The
 * approver is who the case records as `decidedBy`. Both identities come from the authenticated
 * caller — this class never accepts a name from a request body. The four-eyes rule is the fleet
 * maker-checker primitive ([Proposal.approve]), and the table repeats it as a CHECK.
 *
 * Approval and the case transition are separate transactions, in this order: the proposal becomes
 * APPROVED, the case moves, the proposal becomes EXECUTED. A failure in between leaves an APPROVED
 * proposal that any later decide call finishes, because the transition is skipped when the case is
 * already in its target state.
 */
@ApplicationScoped
class MonitoringDispositionService(
    private val proposals: DispositionProposalRepository,
    private val cases: AmlCaseUseCase,
    private val outcomes: DecisionOutcomes,
    private val clock: Clock,
) {
    suspend fun propose(
        caseId: UUID,
        disposition: CaseDisposition,
        outcomeCode: String?,
        note: String?,
        proposer: String,
    ): DispositionProposal {
        require(proposer.isNotBlank()) { "an authenticated proposer is required" }
        require(note == null || note.length <= MAX_NOTE) { "note is too long" }
        val now = clock.instant()
        val case = cases.getCase(caseId)
        val refusal = when {
            !case.isPackMonitoringCase -> "case $caseId was not opened by transaction monitoring"
            !case.canTransitionTo(target(disposition)) ->
                "case $caseId is ${case.status} and cannot be dispositioned ${disposition.name}"
            else -> null
        }
        if (refusal != null) throw DispositionConflictException(refusal)
        val proposal: DispositionProposal = Proposal(
            id = UUID.randomUUID().toString(),
            action = DispositionRequest(
                caseId,
                disposition,
                outcomes.label(outcomeCode, now),
                note?.trim()?.ifBlank {
                    null
                },
            ),
            proposedBy = proposer,
            proposedAt = now,
        )
        if (!proposals.insert(proposal)) {
            throw DispositionConflictException("case $caseId already has a disposition awaiting a decision")
        }
        return proposal
    }

    suspend fun decide(proposalId: UUID, approve: Boolean, reason: String?, checker: String): DispositionProposal {
        require(checker.isNotBlank()) { "an authenticated approver is required" }
        val stored = proposals.find(proposalId) ?: throw DispositionProposalNotFoundException(proposalId)
        val now = clock.instant()
        val decided = when {
            // A proposal approved earlier whose case transition did not finish: finish it.
            stored.state == ProposalState.APPROVED && approve -> stored
            approve -> stored.approve(checker, now, reason).also { claim(it) }
            else -> return stored.reject(checker, now, reason).also { claim(it) }
        }
        val request = decided.action
        val case = cases.getCase(request.caseId)
        if (case.status != target(request.disposition)) {
            cases.updateDecision(
                UpdateAmlDecisionCommand(
                    caseId = request.caseId,
                    targetStatus = target(request.disposition),
                    decisionReason = "outcome ${request.outcome.code}; proposed by ${decided.proposedBy}",
                    assignedAnalyst = case.assignedAnalyst,
                    decidedBy = checkNotNull(decided.decidedBy),
                    fourEyesProposalId = proposalId,
                ),
            )
        }
        val executed = decided.markExecuted(clock.instant())
        proposals.transition(executed, ProposalState.APPROVED)
        return executed
    }

    suspend fun pending(limit: Int): List<DispositionProposal> = proposals.pending(limit.coerceIn(1, MAX_PAGE))

    private suspend fun claim(decided: DispositionProposal) {
        if (!proposals.transition(decided, ProposalState.PROPOSED)) {
            throw DispositionConflictException("proposal ${decided.id} was decided by someone else")
        }
    }

    companion object {
        const val MAX_NOTE = 4_000
        const val MAX_PAGE = 200

        /** REPORT leaves the case ESCALATED: it is now the subject of a suspicious-transaction report. */
        fun target(disposition: CaseDisposition): AmlCaseStatus = when (disposition) {
            CaseDisposition.CLEAR -> AmlCaseStatus.CLEARED
            CaseDisposition.REPORT -> AmlCaseStatus.ESCALATED
        }
    }
}
