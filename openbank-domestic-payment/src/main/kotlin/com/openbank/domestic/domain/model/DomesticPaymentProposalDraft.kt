// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The full immutable instruction a maker prepared. It is not a payment or an approval. */
data class PaymentProposalInstruction(
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: DomesticPaymentPriority,
    val statementLabel: String?,
    val endToEndId: String?,
    /** Preserve canary taint through a future approval and execution boundary. */
    val synthetic: Boolean = false,
) {
    init {
        require(amount.signum() > 0) { "A proposed payment amount must be positive" }
        require(currency == "CZK") { "Domestic payment proposals require CZK" }
        require(creditorAccountNumber.isNotBlank() && creditorBankCode.isNotBlank()) {
            "A proposed payment needs creditor account coordinates"
        }
    }
}

/** DRAFT deliberately has no approval/execution transition until an immutable quorum exists. */
data class DomesticPaymentProposalDraft(
    val id: UUID,
    val makerPartyId: UUID,
    val ownerPartyId: UUID,
    val delegationId: UUID,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val instruction: PaymentProposalInstruction,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        const val SHA256_HEX_LENGTH = 64
    }

    init {
        require(makerPartyId != ownerPartyId) { "A maker cannot impersonate the account owner" }
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            "Idempotency-Key must be 1-128 characters"
        }
        require(requestFingerprint.length == SHA256_HEX_LENGTH) { "Request fingerprint must be SHA-256 hex" }
        require(expiresAt.isAfter(createdAt)) { "Proposal expiry must follow creation" }
    }

    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}
