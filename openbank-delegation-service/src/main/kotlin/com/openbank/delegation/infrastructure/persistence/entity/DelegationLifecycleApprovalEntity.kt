// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.DelegationLifecycleAction
import com.openbank.delegation.domain.model.DelegationLifecycleApproval
import com.openbank.delegation.domain.model.DelegationLifecycleOperation
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(name = "delegation_lifecycle_approvals")
class DelegationLifecycleApprovalEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "delegation_id", nullable = false, updatable = false)
    lateinit var delegationId: UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 16)
    lateinit var operation: DelegationLifecycleOperation

    @Column(name = "requested_reason", nullable = false, updatable = false, length = 500)
    lateinit var requestedReason: String

    @Column(name = "request_key", nullable = false, updatable = false, length = 200)
    lateinit var requestKey: String

    @Column(name = "proposed_by", nullable = false, updatable = false, length = 200)
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false, updatable = false)
    lateinit var proposedAt: OffsetDateTime

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    lateinit var state: ProposalState

    @Column(name = "decided_by", length = 200)
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "decision_reason", length = 500)
    var decisionReason: String? = null

    @Column(name = "executed_at")
    var executedAt: OffsetDateTime? = null

    fun toDomain(): DelegationLifecycleApproval = DelegationLifecycleApproval(
        id = id,
        action = DelegationLifecycleAction(delegationId, operation, requestedReason),
        requestKey = requestKey,
        proposedBy = proposedBy,
        proposedAt = proposedAt.toInstant(),
        state = state,
        decidedBy = decidedBy,
        decidedAt = decidedAt?.toInstant(),
        decisionReason = decisionReason,
        executedAt = executedAt?.toInstant(),
    )

    fun applyDecision(value: DelegationLifecycleApproval) {
        require(id == value.id) { "Cannot apply a decision from another approval" }
        state = value.state
        decidedBy = value.decidedBy
        decidedAt = value.decidedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }
        decisionReason = value.decisionReason
        executedAt = value.executedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }
    }
}
