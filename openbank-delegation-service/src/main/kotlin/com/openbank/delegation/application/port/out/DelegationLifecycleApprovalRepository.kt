// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationLifecycleApproval
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
}

/**
 * Persistence boundary for durable delegation lifecycle approvals.
 *
 * [decideAtomically] locks the proposal and applies its pure [decide] function in one database
 * transaction. This current-main slice only persists rejection evidence. Approval execution stays
 * fail-closed until the repository is stacked on the lifecycle revision/CAS seam.
 */
interface DelegationLifecycleApprovalRepository {
    suspend fun create(candidate: DelegationLifecycleApproval): LifecycleApprovalCreateOutcome
    suspend fun findApproval(id: UUID): DelegationLifecycleApproval?
    suspend fun findByRequestKey(requestKey: String): DelegationLifecycleApproval?
    suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval>

    suspend fun decideAtomically(
        id: UUID,
        decide: (DelegationLifecycleApproval) -> LifecycleApprovalDecision,
    ): DelegationLifecycleApproval?
}
