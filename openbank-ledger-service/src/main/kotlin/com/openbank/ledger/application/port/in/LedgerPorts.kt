// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalance
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class PostJournalCommand(
    val idempotencyKey: String,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val valueDate: LocalDate,
    val description: String?,
    val lines: List<JournalLineRequest>,
    val postedBy: UUID,
    /** Trusted inbound synthetic taint, copied only into this posting's durable outbox events. */
    val synthetic: Boolean = false,
    /**
     * Extra outbox rows to enqueue in the SAME transaction as this posting's own `JournalPosted`
     * + `AccountBookedChanged` rows (#1201 proposed fix 3) — for a caller whose own domain event
     * must inherit the journal post's atomicity guarantee instead of being published separately
     * after the fact, where a crash in the gap between the two would silently lose it. A function
     * of the posted [JournalEntry], not a fixed list: the entry's real `id` (assigned inside
     * [com.openbank.ledger.application.usecase.LedgerService.postJournal], never caller-supplied)
     * is very often exactly what the caller's own event needs as its `aggregateId`, and it isn't
     * known until the entry is built. See `FxRevaluationService.revalue` for the reference use.
     */
    val additionalOutboxMessages: (JournalEntry) -> List<OutboxMessage> = { emptyList() },
)

data class JournalLineRequest(
    val glAccountId: UUID,
    val side: JournalSide,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal?,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
    /** Sub-ledger dimension; allowed only on deposit-control legs (ADR-0039 Phase B). */
    val subAccountId: UUID? = null,
)

data class ReverseJournalCommand(val journalId: UUID, val reason: String, val reversedBy: UUID)

data class GetJournalQuery(val journalId: UUID)
data class GetJournalsByTransactionQuery(val transactionId: UUID)
data class ListJournalsQuery(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val limit: Int = 50,
    val afterCursor: String? = null,
)

data class GetTrialBalanceQuery(val asOf: LocalDate)

/**
 * Per-customer deposit-control sub-ledger balances as of a date (ADR-0039 Phase B). Optionally
 * filtered to a single customer account; used to tie the GL control account out against the
 * balance read-model at account granularity.
 */
data class GetSubLedgerBalancesQuery(val asOf: LocalDate, val subAccountId: UUID? = null)

data class GetControlAccountTieOutQuery(val controlAccountId: UUID, val asOf: LocalDate)

interface LedgerUseCase {
    suspend fun postJournal(command: PostJournalCommand): JournalEntry
    suspend fun reverseJournal(command: ReverseJournalCommand): JournalEntry
    suspend fun getJournal(query: GetJournalQuery): JournalEntry
    suspend fun getJournalsByTransaction(query: GetJournalsByTransactionQuery): List<JournalEntry>
    suspend fun listJournals(query: ListJournalsQuery): CursorPage<JournalEntry>
    suspend fun getTrialBalance(query: GetTrialBalanceQuery): TrialBalance
    suspend fun getSubLedgerBalances(query: GetSubLedgerBalancesQuery): List<SubLedgerBalance>
    suspend fun getControlAccountTieOut(query: GetControlAccountTieOutQuery): List<ControlAccountTieOut>
}
