// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.`in`

import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.domain.model.DelegationLifecycleOperation
import com.openbank.libs.governance.ProposalState
import java.util.UUID

data class ProposeDelegationLifecycleCommand(
    val delegationId: UUID,
    val operation: DelegationLifecycleOperation,
    val reason: String,
    val proposedBy: String,
    val requestKey: String,
)

data class DecideDelegationLifecycleCommand(
    val approvalId: UUID,
    val approve: Boolean,
    val decidedBy: String,
    val reason: String,
)

interface DelegationLifecycleApprovalUseCase {
    suspend fun propose(command: ProposeDelegationLifecycleCommand): DelegationLifecycleApproval
    suspend fun decide(command: DecideDelegationLifecycleCommand): DelegationLifecycleApproval
}

interface DelegationLifecycleApprovalQuery {
    suspend fun get(id: UUID): DelegationLifecycleApproval
    suspend fun list(state: ProposalState?, limit: Int): List<DelegationLifecycleApproval>
}
