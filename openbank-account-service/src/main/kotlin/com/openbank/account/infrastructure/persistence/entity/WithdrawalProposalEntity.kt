// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "savings_withdrawal_proposals")
class WithdrawalProposalEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "delegate_party_id", nullable = false)
    lateinit var delegatePartyId: UUID

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long = 0

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "note")
    var note: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    lateinit var status: WithdrawalProposalStatus

    @Column(name = "approval_id")
    var approvalId: String? = null

    @Column(name = "decided_by")
    var decidedBy: UUID? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "sca_session_id")
    var scaSessionId: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    fun toDomain(): WithdrawalProposal = WithdrawalProposal(
        id = id,
        accountId = accountId,
        delegatePartyId = delegatePartyId,
        amountMinor = amountMinor,
        currency = currency,
        note = note,
        status = status,
        approvalId = approvalId,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        scaSessionId = scaSessionId,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(p: WithdrawalProposal): WithdrawalProposalEntity = WithdrawalProposalEntity().apply {
            id = p.id
            accountId = p.accountId
            delegatePartyId = p.delegatePartyId
            amountMinor = p.amountMinor
            currency = p.currency
            note = p.note
            status = p.status
            approvalId = p.approvalId
            decidedBy = p.decidedBy
            decidedAt = p.decidedAt
            scaSessionId = p.scaSessionId
            createdAt = p.createdAt
        }
    }
}
