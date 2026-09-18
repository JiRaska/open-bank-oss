// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DomesticPaymentProposalDraftRepository
import com.openbank.domestic.application.port.out.PaymentProposalAuthorityPort
import com.openbank.domestic.domain.model.DomesticPaymentProposalDraft
import com.openbank.domestic.domain.model.PaymentProposalInstruction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.util.UUID

sealed interface CreatePaymentProposalDraftOutcome {
    data class Saved(val draft: DomesticPaymentProposalDraft, val replayed: Boolean) :
        CreatePaymentProposalDraftOutcome

    /** Same opaque refusal for an unowned account, missing maker grant, or old/unavailable provider. */
    data object Refused : CreatePaymentProposalDraftOutcome

    data object IdempotencyConflict : CreatePaymentProposalDraftOutcome
}

/** Prepares an immutable instruction; it has no approval or payment-execution capability. */
@ApplicationScoped
class DomesticPaymentProposalDraftService(
    private val accounts: AccountLookupPort,
    private val authority: PaymentProposalAuthorityPort,
    private val drafts: DomesticPaymentProposalDraftRepository,
    private val clock: Clock,
) {
    suspend fun create(makerPartyId: UUID, raw: CreateDomesticPaymentCommand): CreatePaymentProposalDraftOutcome {
        validateDraftRequest(raw)
        // Replace caller-supplied actor fields before fingerprinting. Only the authenticated edge
        // identity supplied in makerPartyId can name the person who prepared this draft.
        val normalized = DomesticPaymentRequestFingerprint.normalize(
            raw.copy(actorId = makerPartyId, actorScope = makerPartyId.toString()),
        )
        val iban = CzechDomesticIban.fromAccountNumber(
            accountNumber = normalized.debtorAccountNumber,
            bankCode = normalized.debtorBankCode,
        ) ?: return CreatePaymentProposalDraftOutcome.Refused
        require(
            normalized.creditorAccountNumber.length <= MAX_CREDITOR_ACCOUNT_LENGTH &&
                normalized.creditorBankCode.length <= MAX_BANK_CODE_LENGTH,
        ) { "Creditor account coordinates exceed the supported length" }
        if (accounts.findAccountIdByIban(iban) != normalized.debtorAccountId) {
            return CreatePaymentProposalDraftOutcome.Refused
        }
        val owner = accounts.findPartyByAccountId(normalized.debtorAccountId)
            ?: return CreatePaymentProposalDraftOutcome.Refused
        if (owner == makerPartyId) return CreatePaymentProposalDraftOutcome.Refused
        val proof = authority.authorize(
            normalized.debtorAccountId,
            makerPartyId,
            normalized.amount,
            normalized.currency,
        ) ?: return CreatePaymentProposalDraftOutcome.Refused
        if (proof.ownerPartyId != owner) return CreatePaymentProposalDraftOutcome.Refused

        val fingerprint = DomesticPaymentRequestFingerprint.sha256(normalized)
        drafts.findByMakerAndKey(makerPartyId, normalized.idempotencyKey)?.let { existing ->
            return replayOrConflict(existing, owner, proof.delegationId, fingerprint, replayed = true)
        }
        val now = clock.instant()
        val candidate = DomesticPaymentProposalDraft(
            id = UUID.randomUUID(),
            makerPartyId = makerPartyId,
            ownerPartyId = owner,
            delegationId = proof.delegationId,
            idempotencyKey = normalized.idempotencyKey,
            requestFingerprint = fingerprint,
            instruction = normalized.toProposalInstruction(),
            createdAt = now,
            expiresAt = now.plus(DRAFT_TTL),
        )
        val winner = drafts.saveOrGetWinner(candidate)
        return replayOrConflict(winner, owner, proof.delegationId, fingerprint, replayed = winner.id != candidate.id)
    }

    private fun validateDraftRequest(raw: CreateDomesticPaymentCommand) {
        require(raw.idempotencyKey.isNotBlank() && raw.idempotencyKey.length <= MAX_KEY_LENGTH) {
            "Idempotency-Key must be 1-128 characters"
        }
        require(raw.amount.signum() > 0 && raw.currency.trim().uppercase() == "CZK") {
            "A domestic payment proposal requires a positive CZK amount"
        }
        require(
            raw.amount.scale() <= MAX_AMOUNT_SCALE &&
                raw.amount.precision() - raw.amount.scale() <= MAX_INTEGER_DIGITS,
        ) {
            "A proposed amount exceeds the supported precision"
        }
        require(raw.delegationId == null && raw.reservationId == null) {
            "A maker draft cannot carry debit authority or a spend reservation"
        }
        require(raw.transferScope == null && raw.technicalAccountCode == null) {
            "A maker draft cannot select an execution route or technical account"
        }
    }

    private fun replayOrConflict(
        draft: DomesticPaymentProposalDraft,
        ownerPartyId: UUID,
        delegationId: UUID,
        fingerprint: String,
        replayed: Boolean,
    ): CreatePaymentProposalDraftOutcome {
        val sameAuthority = draft.ownerPartyId == ownerPartyId && draft.delegationId == delegationId
        return if (sameAuthority && draft.requestFingerprint == fingerprint) {
            CreatePaymentProposalDraftOutcome.Saved(draft, replayed)
        } else {
            CreatePaymentProposalDraftOutcome.IdempotencyConflict
        }
    }

    private fun CreateDomesticPaymentCommand.toProposalInstruction() = PaymentProposalInstruction(
        debtorAccountId = debtorAccountId,
        debtorAccountNumber = debtorAccountNumber,
        debtorBankCode = debtorBankCode,
        debtorName = debtorName,
        creditorAccountNumber = creditorAccountNumber,
        creditorBankCode = creditorBankCode,
        creditorName = creditorName,
        amount = amount,
        currency = currency,
        variableSymbol = variableSymbol,
        specificSymbol = specificSymbol,
        constantSymbol = constantSymbol,
        messageForPayee = messageForPayee,
        priority = priority,
        statementLabel = statementLabel,
        endToEndId = endToEndId,
        synthetic = synthetic,
    )

    private companion object {
        const val MAX_KEY_LENGTH = 128
        const val MAX_AMOUNT_SCALE = 6
        const val MAX_INTEGER_DIGITS = 14
        const val MAX_CREDITOR_ACCOUNT_LENGTH = 34
        const val MAX_BANK_CODE_LENGTH = 4
        val DRAFT_TTL: Duration = Duration.ofDays(7)
    }
}
