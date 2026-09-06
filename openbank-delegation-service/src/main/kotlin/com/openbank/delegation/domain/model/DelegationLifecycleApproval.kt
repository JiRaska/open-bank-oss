// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import java.time.Instant
import java.util.UUID

/** Bank-side delegation transitions that may enter the durable maker-checker workflow. */
enum class DelegationLifecycleOperation {
    SUSPEND,
    REINSTATE,
    REVOKE,
}

/**
 * The immutable action proposed by the maker. The operational reason is deliberately separate
 * from the checker's [DelegationLifecycleApproval.decisionReason]: both are evidence and neither
 * may be rewritten after the proposal is created.
 */
data class DelegationLifecycleAction(
    val delegationId: UUID,
    val operation: DelegationLifecycleOperation,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "Lifecycle reason is required" }
        require(reason.length <= MAX_REASON_LENGTH) { "Lifecycle reason must be at most $MAX_REASON_LENGTH characters" }
    }

    companion object {
        const val MAX_REASON_LENGTH = 500
    }
}

/**
 * Durable four-eyes evidence for one bank-side lifecycle action.
 *
 * This wraps the shared [Proposal] state machine rather than defining a second maker-checker rule.
 * Only PROPOSED, REJECTED and EXECUTED are valid persisted evidence states. EXECUTED is written
 * only with a revision-matched lifecycle transition and its transactional outbox event; there is
 * no transient APPROVED state that could claim a decision without the actual side effect.
 */
data class DelegationLifecycleApproval(
    val id: UUID,
    val action: DelegationLifecycleAction,
    val requestKey: String,
    val proposedBy: String,
    val proposedAt: Instant,
    /** Grant revision observed by the maker; legacy NULL evidence is intentionally non-executable. */
    val expectedLifecycleRevision: Long? = null,
    val state: ProposalState = ProposalState.PROPOSED,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
    val executedAt: Instant? = null,
) {
    init {
        require(requestKey.isNotBlank()) { "X-Request-ID is required" }
        require(requestKey.length <= MAX_REQUEST_KEY_LENGTH) {
            "X-Request-ID must be at most $MAX_REQUEST_KEY_LENGTH characters"
        }
        require(proposedBy.isNotBlank()) { "Proposer identity is required" }
        require(proposedBy.length <= MAX_ACTOR_LENGTH) { "Proposer identity is too long" }
        require(expectedLifecycleRevision == null || expectedLifecycleRevision >= 0) {
            "Expected lifecycle revision must be non-negative"
        }
        require(state in PERSISTED_STATES) { "Lifecycle approval state $state is not persisted" }
        require(decisionReason == null || decisionReason.length <= DelegationLifecycleAction.MAX_REASON_LENGTH) {
            "Decision reason must be at most ${DelegationLifecycleAction.MAX_REASON_LENGTH} characters"
        }
        when (state) {
            ProposalState.PROPOSED ->
                require(
                    decidedBy == null &&
                        decidedAt == null &&
                        decisionReason == null &&
                        executedAt == null,
                ) {
                    "A proposed lifecycle approval cannot carry decision evidence"
                }

            ProposalState.REJECTED ->
                require(
                    decidedBy != null &&
                        decidedAt != null &&
                        !decisionReason.isNullOrBlank() &&
                        executedAt == null,
                ) {
                    "A rejected lifecycle approval requires checker, reason and decision time"
                }

            ProposalState.EXECUTED ->
                require(
                    decidedBy != null &&
                        decidedAt != null &&
                        !decisionReason.isNullOrBlank() &&
                        executedAt != null,
                ) {
                    "An executed lifecycle approval requires checker, reason and execution time"
                }

            else -> error("unreachable persisted lifecycle approval state: $state")
        }
    }

    fun toProposal(): Proposal<DelegationLifecycleAction> = Proposal(
        id = id.toString(),
        action = action,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        state = state,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        executedAt = executedAt,
    )

    fun withProposal(proposal: Proposal<DelegationLifecycleAction>): DelegationLifecycleApproval = copy(
        state = proposal.state,
        decidedBy = proposal.decidedBy,
        decidedAt = proposal.decidedAt,
        decisionReason = proposal.decisionReason,
        executedAt = proposal.executedAt,
    )

    companion object {
        const val MAX_ACTOR_LENGTH = 200
        const val MAX_REQUEST_KEY_LENGTH = 200
        private val PERSISTED_STATES = setOf(ProposalState.PROPOSED, ProposalState.REJECTED, ProposalState.EXECUTED)
    }
}
