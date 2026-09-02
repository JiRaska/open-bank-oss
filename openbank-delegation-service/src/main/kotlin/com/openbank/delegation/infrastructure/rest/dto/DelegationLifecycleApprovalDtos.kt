// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest.dto

import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.domain.model.DelegationLifecycleOperation
import com.openbank.libs.governance.ProposalState
import java.time.Instant
import java.util.UUID

data class ProposeDelegationLifecycleRequest(
    val delegationId: UUID,
    val operation: DelegationLifecycleOperation,
    val reason: String,
)

data class DecideDelegationLifecycleRequest(val approve: Boolean, val reason: String)

data class DelegationLifecycleApprovalResponse(
    val id: UUID,
    val delegationId: UUID,
    val operation: DelegationLifecycleOperation,
    val requestedReason: String,
    val state: ProposalState,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val executedAt: Instant?,
) {
    companion object {
        fun from(value: DelegationLifecycleApproval): DelegationLifecycleApprovalResponse =
            DelegationLifecycleApprovalResponse(
                id = value.id,
                delegationId = value.action.delegationId,
                operation = value.action.operation,
                requestedReason = value.action.reason,
                state = value.state,
                proposedBy = value.proposedBy,
                proposedAt = value.proposedAt,
                decidedBy = value.decidedBy,
                decidedAt = value.decidedAt,
                decisionReason = value.decisionReason,
                executedAt = value.executedAt,
            )
    }
}
