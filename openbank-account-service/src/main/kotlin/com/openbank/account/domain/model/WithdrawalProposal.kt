// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A delegate's propose-only withdrawal proposal (ADR-0232 D8 / issue #2990 AC8).
 * The delegate (maker) can never execute; the owner's SCA-bound decision is the
 * only path to APPROVED, which emits the executable instruction as an outbox event
 * for the payments path.
 */
enum class WithdrawalProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
}

private const val ISO_CURRENCY_CODE_LENGTH = 3

data class WithdrawalProposal(
    val id: UUID,
    val accountId: UUID,
    val delegatePartyId: UUID,
    val amountMinor: Long,
    val currency: String,
    val note: String? = null,
    val status: WithdrawalProposalStatus = WithdrawalProposalStatus.PENDING,
    val approvalId: String? = null,
    val decidedBy: UUID? = null,
    val decidedAt: OffsetDateTime? = null,
    val scaSessionId: UUID? = null,
    val createdAt: OffsetDateTime,
) {
    init {
        require(amountMinor > 0) { "amountMinor must be positive" }
        require(currency.length == ISO_CURRENCY_CODE_LENGTH) { "currency must be ISO 4217" }
    }

    fun approve(by: UUID, scaSessionId: UUID, now: OffsetDateTime): WithdrawalProposal {
        check(status == WithdrawalProposalStatus.PENDING) { "only a PENDING proposal can be approved (is $status)" }
        return copy(
            status = WithdrawalProposalStatus.APPROVED,
            decidedBy = by,
            decidedAt = now,
            scaSessionId = scaSessionId,
        )
    }

    fun reject(by: UUID, now: OffsetDateTime): WithdrawalProposal {
        check(status == WithdrawalProposalStatus.PENDING) { "only a PENDING proposal can be rejected (is $status)" }
        return copy(status = WithdrawalProposalStatus.REJECTED, decidedBy = by, decidedAt = now)
    }

    fun cancel(now: OffsetDateTime): WithdrawalProposal {
        check(status == WithdrawalProposalStatus.PENDING) { "only a PENDING proposal can be cancelled (is $status)" }
        return copy(status = WithdrawalProposalStatus.CANCELLED, decidedAt = now)
    }
}
