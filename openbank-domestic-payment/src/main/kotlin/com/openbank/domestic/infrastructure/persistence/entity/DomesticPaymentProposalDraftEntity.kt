// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.persistence.entity

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.domain.model.DomesticPaymentProposalDraft
import com.openbank.domestic.domain.model.PaymentProposalInstruction
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "domestic_payment_proposal_drafts")
class DomesticPaymentProposalDraftEntity : PanacheEntity() {
    @Column(name = "proposal_id", nullable = false, unique = true)
    lateinit var proposalId: UUID

    @Column(name = "maker_party_id", nullable = false)
    lateinit var makerPartyId: UUID

    @Column(name = "owner_party_id", nullable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "delegation_id", nullable = false)
    lateinit var delegationId: UUID

    @Column(name = "debtor_account_id", nullable = false)
    lateinit var debtorAccountId: UUID

    @Column(name = "idempotency_key", nullable = false, length = 128)
    lateinit var idempotencyKey: String

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    lateinit var requestFingerprint: String

    @Column(name = "instruction_json", nullable = false, columnDefinition = "text")
    lateinit var instructionJson: String

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "creditor_account_number", nullable = false, length = 34)
    lateinit var creditorAccountNumber: String

    @Column(name = "creditor_bank_code", nullable = false, length = 4)
    lateinit var creditorBankCode: String

    @Column(name = "status", nullable = false, length = 32)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    fun toDomain(mapper: ObjectMapper): DomesticPaymentProposalDraft {
        check(status == "DRAFT") { "Unknown proposal draft status: $status" }
        val instruction = mapper.readValue(instructionJson, PaymentProposalInstruction::class.java)
        check(instruction.debtorAccountId == debtorAccountId) { "Proposal account snapshot disagrees with instruction" }
        check(instruction.amount.compareTo(amount) == 0 && instruction.currency == currency) {
            "Proposal money snapshot disagrees with instruction"
        }
        check(
            instruction.creditorAccountNumber == creditorAccountNumber &&
                instruction.creditorBankCode == creditorBankCode,
        ) { "Proposal payee snapshot disagrees with instruction" }
        return DomesticPaymentProposalDraft(
            id = proposalId,
            makerPartyId = makerPartyId,
            ownerPartyId = ownerPartyId,
            delegationId = delegationId,
            idempotencyKey = idempotencyKey,
            requestFingerprint = requestFingerprint,
            instruction = instruction,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    companion object {
        fun fromDomain(draft: DomesticPaymentProposalDraft, mapper: ObjectMapper): DomesticPaymentProposalDraftEntity =
            DomesticPaymentProposalDraftEntity().apply {
                proposalId = draft.id
                makerPartyId = draft.makerPartyId
                ownerPartyId = draft.ownerPartyId
                delegationId = draft.delegationId
                debtorAccountId = draft.instruction.debtorAccountId
                idempotencyKey = draft.idempotencyKey
                requestFingerprint = draft.requestFingerprint
                instructionJson = mapper.writeValueAsString(draft.instruction)
                amount = draft.instruction.amount
                currency = draft.instruction.currency
                creditorAccountNumber = draft.instruction.creditorAccountNumber
                creditorBankCode = draft.instruction.creditorBankCode
                status = "DRAFT"
                createdAt = draft.createdAt
                expiresAt = draft.expiresAt
            }
    }
}
