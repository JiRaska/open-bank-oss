// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.DecideDelegationLifecycleCommand
import com.openbank.delegation.application.port.`in`.DelegationLifecycleApprovalQuery
import com.openbank.delegation.application.port.`in`.DelegationLifecycleApprovalUseCase
import com.openbank.delegation.application.port.`in`.ProposeDelegationLifecycleCommand
import com.openbank.delegation.application.port.out.DelegationLifecycleApprovalRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.LifecycleApprovalCreateOutcome
import com.openbank.delegation.application.port.out.LifecycleApprovalDecision
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationLifecycleAction
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.domain.model.DelegationLifecycleOperation
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.governance.ProposalState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.util.UUID

class DelegationLifecycleApprovalNotFound(id: UUID) :
    RuntimeException("Delegation lifecycle approval not found: $id")

class DelegationLifecycleApprovalConflict(message: String) : RuntimeException(message)

/**
 * Durable maker-checker workflow for bank-side delegation lifecycle actions.
 *
 * The mutation REST edge is default-off. Proposal and rejection evidence are durable and
 * idempotent. Approval execution is intentionally fail-closed in this current-main slice: it must
 * first be stacked on the lifecycle revision/CAS seam so no operator path can overwrite a newer
 * grant transition or emit an unstamped event.
 */
@ApplicationScoped
class DelegationLifecycleApprovalService(
    private val approvals: DelegationLifecycleApprovalRepository,
    private val delegations: DelegationRepository,
    private val clock: Clock,
) : DelegationLifecycleApprovalUseCase,
    DelegationLifecycleApprovalQuery {

    @Inject
    constructor(
        approvals: DelegationLifecycleApprovalRepository,
        delegations: DelegationRepository,
    ) : this(approvals, delegations, Clock.systemUTC())

    override suspend fun propose(command: ProposeDelegationLifecycleCommand): DelegationLifecycleApproval {
        val actor = command.proposedBy.trim()
        val requestKey = command.requestKey.trim()
        val reason = command.reason.trim()
        val action = DelegationLifecycleAction(command.delegationId, command.operation, reason)
        approvals.findByRequestKey(requestKey)?.let { existing ->
            requireSameProposal(existing, action, actor, requestKey)
            return existing
        }
        val grant = delegations.findById(command.delegationId)
            ?: throw DelegationNotFoundException(command.delegationId)
        requireActionCanStart(action, grant)

        val candidate = DelegationLifecycleApproval(
            id = Ids.newId(),
            action = action,
            requestKey = requestKey,
            proposedBy = actor,
            proposedAt = clock.instant(),
        )
        return when (val outcome = approvals.create(candidate)) {
            is LifecycleApprovalCreateOutcome.Created -> outcome.approval
            is LifecycleApprovalCreateOutcome.Replayed -> {
                val existing = outcome.approval
                requireSameProposal(existing, candidate.action, candidate.proposedBy, candidate.requestKey)
                existing
            }
        }
    }

    override suspend fun decide(command: DecideDelegationLifecycleCommand): DelegationLifecycleApproval {
        val checker = command.decidedBy.trim()
        val reason = command.reason.trim()
        require(checker.isNotBlank()) { "Checker identity is required" }
        require(checker.length <= DelegationLifecycleApproval.MAX_ACTOR_LENGTH) { "Checker identity is too long" }
        require(reason.isNotBlank()) { "Decision reason is required" }
        require(reason.length <= DelegationLifecycleAction.MAX_REASON_LENGTH) {
            "Decision reason must be at most ${DelegationLifecycleAction.MAX_REASON_LENGTH} characters"
        }
        val at = clock.instant()

        return approvals.decideAtomically(command.approvalId) { approval ->
            if (checker == approval.proposedBy) {
                throw SelfApprovalNotAllowedException(approval.proposedBy)
            }
            if (approval.state != ProposalState.PROPOSED) {
                if (isIdempotentReplay(approval, command.approve, checker, reason)) {
                    LifecycleApprovalDecision.Replayed(approval)
                } else {
                    throw DelegationLifecycleApprovalConflict(
                        "Lifecycle approval ${approval.id} is ${approval.state} and cannot be decided again",
                    )
                }
            } else {
                if (command.approve) {
                    throw DelegationLifecycleApprovalConflict(
                        "Lifecycle approval execution is unavailable until revision-safe execution is deployed",
                    )
                } else {
                    val rejected = approval.withProposal(approval.toProposal().reject(checker, at, reason))
                    LifecycleApprovalDecision.Rejected(rejected)
                }
            }
        } ?: throw DelegationLifecycleApprovalNotFound(command.approvalId)
    }

    override suspend fun get(id: UUID): DelegationLifecycleApproval =
        approvals.findApproval(id) ?: throw DelegationLifecycleApprovalNotFound(id)

    override suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval> =
        approvals.list(requirePersistedState(state), limit.coerceIn(1, MAX_PAGE_SIZE))

    private fun requireSameProposal(
        existing: DelegationLifecycleApproval,
        action: DelegationLifecycleAction,
        proposedBy: String,
        requestKey: String,
    ) {
        if (existing.action != action || existing.proposedBy != proposedBy) {
            throw DelegationLifecycleApprovalConflict(
                "X-Request-ID '$requestKey' already belongs to another lifecycle proposal",
            )
        }
    }

    private fun requirePersistedState(state: ProposalState?): ProposalState? {
        require(state == null || state in PERSISTED_STATES) {
            "Lifecycle approval state $state is not queryable"
        }
        return state
    }

    private fun isIdempotentReplay(
        approval: DelegationLifecycleApproval,
        approve: Boolean,
        checker: String,
        reason: String,
    ): Boolean {
        val expected = if (approve) ProposalState.EXECUTED else ProposalState.REJECTED
        return approval.state == expected && approval.decidedBy == checker && approval.decisionReason == reason
    }

    private fun requireActionCanStart(action: DelegationLifecycleAction, grant: DelegationGrant) {
        val allowed = when (action.operation) {
            DelegationLifecycleOperation.SUSPEND -> grant.status == DelegationStatus.ACTIVE
            DelegationLifecycleOperation.REINSTATE -> grant.status == DelegationStatus.SUSPENDED
            DelegationLifecycleOperation.REVOKE -> grant.status in setOf(
                DelegationStatus.OFFERED,
                DelegationStatus.ACTIVE,
                DelegationStatus.SUSPENDED,
            )
        }
        if (!allowed) {
            throw DelegationLifecycleApprovalConflict(
                "${action.operation} cannot be proposed for delegation ${grant.id} in state ${grant.status}",
            )
        }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
        val PERSISTED_STATES = setOf(ProposalState.PROPOSED, ProposalState.REJECTED, ProposalState.EXECUTED)
    }
}
