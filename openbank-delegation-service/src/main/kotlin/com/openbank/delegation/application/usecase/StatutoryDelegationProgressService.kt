// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

data class StatutorySigningProgress(
    val operationId: UUID,
    val requiredSignatures: Int,
    val approvalCount: Int,
    val rejectionCount: Int,
    val quorumSatisfied: Boolean,
    val quorumPossible: Boolean,
    val myVerdict: StatutoryDecisionVerdict?,
)

/** Display-only status from the same office-aware rule used by the execution gate. */
@ApplicationScoped
class StatutoryDelegationProgressService(
    private val proposals: StatutoryDelegationProposalService,
    private val repository: StatutoryDelegationOperationRepository,
) {
    suspend fun get(id: UUID, principal: UUID, actor: UUID): StatutorySigningProgress {
        val (operation, rule) = proposals.current(id, principal, principal, actor)
        if (operation.state != StatutoryOperationState.PENDING) throw StatutoryProposalStale(id)
        val decisions = repository.decisions(id)
        val approved = decisions.filter { it.verdict == StatutoryDecisionVerdict.APPROVE }
            .map { it.actorPartyId }.toSet()
        val rejected = decisions.filter { it.verdict == StatutoryDecisionVerdict.REJECT }
            .map { it.actorPartyId }.toSet()
        val eligible = rule.eligibleRepresentatives.map { it.partyId }.toSet()
        return StatutorySigningProgress(
            operationId = id,
            requiredSignatures = rule.requiredSignatures,
            approvalCount = approved.size,
            rejectionCount = rejected.size,
            quorumSatisfied = rule.satisfiedBy(approved),
            quorumPossible = rule.satisfiedBy(eligible - rejected),
            myVerdict = decisions.singleOrNull { it.actorPartyId == actor }?.verdict,
        )
    }
}
