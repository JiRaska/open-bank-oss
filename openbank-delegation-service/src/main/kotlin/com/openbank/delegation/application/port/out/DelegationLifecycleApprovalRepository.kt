// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.governance.ProposalState
import java.util.UUID

sealed interface LifecycleApprovalCreateOutcome {
    val approval: DelegationLifecycleApproval

    data class Created(override val approval: DelegationLifecycleApproval) : LifecycleApprovalCreateOutcome
    data class Replayed(override val approval: DelegationLifecycleApproval) : LifecycleApprovalCreateOutcome
}

/** Pure decision plan produced while the proposal row is locked. */
sealed interface LifecycleApprovalDecision {
    val approval: DelegationLifecycleApproval

    /** Same terminal decision retried by the same checker; no write and no second side effect. */
    data class Replayed(override val approval: DelegationLifecycleApproval) : LifecycleApprovalDecision

    data class Rejected(override val approval: DelegationLifecycleApproval) : LifecycleApprovalDecision

    /** A revision-checked lifecycle transition and its event, persisted with the decision. */
    data class Executed(
        override val approval: DelegationLifecycleApproval,
        val grant: DelegationGrant,
        val event: DomainEvent,
    ) : LifecycleApprovalDecision
}

/**
 * Persistence boundary for durable delegation lifecycle approvals.
 *
 * [decideAtomically] locks the proposal and its referenced grant, applies [decide], then persists
 * a rejected decision or the grant CAS, outbox event and EXECUTED evidence in one transaction.
 */
interface DelegationLifecycleApprovalRepository {
    suspend fun create(candidate: DelegationLifecycleApproval): LifecycleApprovalCreateOutcome
    suspend fun findApproval(id: UUID): DelegationLifecycleApproval?
    suspend fun findByRequestKey(requestKey: String): DelegationLifecycleApproval?
    suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval>

    suspend fun decideAtomically(
        id: UUID,
        decide: (DelegationLifecycleApproval, DelegationGrant?) -> LifecycleApprovalDecision,
    ): DelegationLifecycleApproval?
}
