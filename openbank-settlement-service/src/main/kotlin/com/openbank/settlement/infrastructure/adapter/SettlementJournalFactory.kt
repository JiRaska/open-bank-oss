// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.infrastructure.client.JournalLineRequest
import com.openbank.settlement.infrastructure.client.PostJournalRequest
import java.math.BigDecimal
import java.util.UUID

/**
 * Builds the ledger `PostJournalRequest` for a settlement booking. Shared by
 * [LedgerBookAdapter.book] (production) and the settlement→ledger consumer pact
 * (`SettlementLedgerPostJournalPactConsumerTest`) so the contract verifies the request THIS
 * factory produces, not a hand-typed JSON literal that can silently drift from it (issue #1347).
 * Mirrors the JournalFactory pattern billing/lending/transaction/interest already use for the same
 * postJournal contract — and closes a concrete drift the literal had carried: it declared an
 * `"fxRate": null` field per line that the real [JournalLineRequest] does not even have, so the
 * old contract verified a body the adapter never sends.
 */
object SettlementJournalFactory {
    /** The settlement-intrinsic inputs of a booking (grouped so [build] stays within the
     * detekt LongParameterList threshold and reads as one cohesive posting). */
    data class Posting(
        val settlementId: UUID,
        val amount: BigDecimal,
        val currency: String,
        val payerAccountId: UUID,
        val payeeAccountId: UUID,
    )

    fun build(
        posting: Posting,
        glDebitAccountId: UUID,
        glCreditAccountId: UUID,
        date: String,
        createdBy: UUID,
    ): PostJournalRequest = PostJournalRequest(
        idempotencyKey = "settlement-book-${posting.settlementId}",
        transactionId = posting.settlementId,
        entryDate = date,
        valueDate = date,
        description = "Settlement booking ${posting.settlementId}",
        createdBy = createdBy,
        lines = listOf(
            JournalLineRequest(
                glAccountId = glDebitAccountId,
                side = "DEBIT",
                amount = posting.amount,
                currencyCode = posting.currency,
                baseAmount = posting.amount,
                baseCurrencyCode = posting.currency,
                subAccountId = posting.payerAccountId,
            ),
            JournalLineRequest(
                glAccountId = glCreditAccountId,
                side = "CREDIT",
                amount = posting.amount,
                currencyCode = posting.currency,
                baseAmount = posting.amount,
                baseCurrencyCode = posting.currency,
                subAccountId = posting.payeeAccountId,
            ),
        ),
    )
}
