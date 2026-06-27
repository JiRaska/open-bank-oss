// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.PostingKind
import java.time.LocalDate
import java.util.UUID

/** The leaf GL accounts the loan book debits/credits. Config-driven so ops map them to the real chart. */
data class LendingGlAccounts(
    val loansReceivable: UUID,
    val fundingClearing: UUID,
    val interestIncome: UUID,
    val interestReceivable: UUID,
    val loanLossExpense: UUID,
)

/**
 * Turns a lending [LedgerPosting] into a balanced double-entry [PostJournalRequest] for ledger-service
 * (ADR-0028 D3). Pure and side-effect-free so the accounting is fully unit-tested without any HTTP.
 *
 * Loans are single-currency, so every entry is two-legged and self-balances within that currency
 * (the ledger enforces debits == credits per currency, ADR-0025). Posting kinds map as:
 *
 *   DISBURSEMENT         DEBIT  Loans Receivable     CREDIT Funding Clearing     (asset created, cash out)
 *   PRINCIPAL_REPAYMENT  DEBIT  Funding Clearing     CREDIT Loans Receivable     (cash in, asset reduced)
 *   INTEREST             DEBIT  Funding Clearing     CREDIT Interest Income       (cash in, income earned — early payment)
 *   INTEREST_ACCRUAL     DEBIT  Interest Receivable  CREDIT Interest Income       (income earned at due date, no cash yet)
 *   INTEREST_SETTLEMENT  DEBIT  Funding Clearing     CREDIT Interest Receivable   (cash in, accrued receivable cleared)
 *   WRITE_OFF            DEBIT  Loan Loss Expense    CREDIT Loans Receivable      (loss booked, asset off)
 */
object LendingJournalFactory {

    fun buildRequest(
        posting: LedgerPosting,
        accounts: LendingGlAccounts,
        systemActorId: UUID,
        date: LocalDate,
    ): PostJournalRequest = PostJournalRequest(
        // The reference is already unique per economic event (e.g. "loan:<id>:disbursement"),
        // so it doubles as the ledger idempotency key — replays collapse to one journal.
        idempotencyKey = posting.reference,
        transactionId = UUID.nameUUIDFromBytes(posting.reference.toByteArray(Charsets.UTF_8)),
        entryDate = date.toString(),
        valueDate = date.toString(),
        description = "Lending ${posting.kind.name.lowercase().replace('_', ' ')}: ${posting.reference}",
        lines = buildLines(posting, accounts),
        createdBy = systemActorId,
    )

    fun buildLines(posting: LedgerPosting, accounts: LendingGlAccounts): List<JournalLineRequest> {
        val (debit, credit) = when (posting.kind) {
            PostingKind.DISBURSEMENT -> accounts.loansReceivable to accounts.fundingClearing
            PostingKind.PRINCIPAL_REPAYMENT -> accounts.fundingClearing to accounts.loansReceivable
            PostingKind.INTEREST -> accounts.fundingClearing to accounts.interestIncome
            PostingKind.INTEREST_ACCRUAL -> accounts.interestReceivable to accounts.interestIncome
            PostingKind.INTEREST_SETTLEMENT -> accounts.fundingClearing to accounts.interestReceivable
            PostingKind.WRITE_OFF -> accounts.loanLossExpense to accounts.loansReceivable
        }
        val ccy = posting.amount.currency.code
        val value = posting.amount.amount
        return listOf(
            JournalLineRequest(debit, "DEBIT", value, ccy, null, value, ccy),
            JournalLineRequest(credit, "CREDIT", value, ccy, null, value, ccy),
        )
    }
}
