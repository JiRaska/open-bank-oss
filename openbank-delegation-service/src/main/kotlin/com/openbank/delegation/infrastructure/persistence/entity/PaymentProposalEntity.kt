// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalPayload
import com.openbank.delegation.domain.model.PaymentProposalStatus
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** V38. The instruction columns are `updatable = false`; the V38 trigger pins them as well. */
@Entity
@Table(name = "delegation_payment_proposals")
class PaymentProposalEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(name = "grant_id", nullable = false, updatable = false)
    lateinit var grantId: UUID

    @Column(name = "mcp_client_id", nullable = false, updatable = false, length = 255)
    lateinit var mcpClientId: String

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 32)
    lateinit var operationType: PaymentProposalOperationType

    @Column(name = "debtor_account_id", nullable = false, updatable = false)
    lateinit var debtorAccountId: UUID

    @Column(name = "creditor_iban", nullable = false, updatable = false, length = 34)
    lateinit var creditorIban: String

    @Column(name = "creditor_name", nullable = false, updatable = false, length = 70)
    lateinit var creditorName: String

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String

    @Column(name = "reference", updatable = false, length = 140)
    var reference: String? = null

    @Column(name = "payload_sha256", nullable = false, updatable = false, length = 64)
    lateinit var payloadSha256: String

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    lateinit var status: PaymentProposalStatus

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: OffsetDateTime

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "executed_payment_id")
    var executedPaymentId: UUID? = null

    fun toDomain(): PaymentProposal = PaymentProposal(
        id = id,
        partyId = partyId,
        grantId = grantId,
        mcpClientId = mcpClientId,
        payload = PaymentProposalPayload(
            operationType = operationType,
            debtorAccountId = debtorAccountId,
            creditorIban = creditorIban,
            creditorName = creditorName,
            amount = amount,
            currency = currency,
            reference = reference,
        ),
        // The domain constructor recomputes the hash and refuses a row whose stored hash does not
        // describe its own columns: a tampered row fails to load rather than being approvable.
        payloadSha256 = payloadSha256,
        status = status,
        createdAt = createdAt,
        expiresAt = expiresAt,
        revision = revision,
        decidedAt = decidedAt,
        executedPaymentId = executedPaymentId,
    )

    companion object {
        fun fromDomain(p: PaymentProposal): PaymentProposalEntity = PaymentProposalEntity().apply {
            id = p.id
            partyId = p.partyId
            grantId = p.grantId
            mcpClientId = p.mcpClientId
            operationType = p.payload.operationType
            debtorAccountId = p.payload.debtorAccountId
            creditorIban = p.payload.creditorIban
            creditorName = p.payload.creditorName
            amount = p.payload.amount
            currency = p.payload.currency
            reference = p.payload.reference
            payloadSha256 = p.payloadSha256
            status = p.status
            revision = p.revision
            createdAt = p.createdAt
            expiresAt = p.expiresAt
            decidedAt = p.decidedAt
            executedPaymentId = p.executedPaymentId
        }
    }
}
