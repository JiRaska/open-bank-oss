// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.OffsetDateTime
import java.util.UUID

data class WithdrawalApprovalDecisionId(var proposalId: UUID? = null, var partyId: UUID? = null) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Entity
@IdClass(WithdrawalApprovalDecisionId::class)
@Table(name = "savings_withdrawal_approval_decisions")
class WithdrawalApprovalDecisionEntity {
    @Id
    @Column(name = "proposal_id", nullable = false, updatable = false)
    lateinit var proposalId: UUID

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(name = "approved", nullable = false, updatable = false)
    var approved: Boolean = false

    @Column(name = "sca_session_id", nullable = false, updatable = false, unique = true)
    lateinit var scaSessionId: UUID

    @Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: OffsetDateTime
}
