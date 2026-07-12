// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.LocalDate
import java.util.UUID

/** Outbound persistence port for the general-ledger chart of accounts. */
interface GlAccountRepository {

    suspend fun findById(id: UUID): GlAccount?

    suspend fun findByCode(code: String): GlAccount?

    suspend fun save(account: GlAccount): GlAccount
}

/**
 * Outbound persistence port for double-entry journal entries.
 *
 * [save] and [saveReversal] also enqueue a [OutboxMessage] so the journal posting and its
 * domain-event outbox row are written in the SAME database transaction (transactional outbox,
 * ADR-0003): either both commit or neither does.
 */
interface JournalRepository {

    suspend fun findById(id: UUID): JournalEntry?

    suspend fun findByTransactionId(transactionId: UUID): List<JournalEntry>

    suspend fun findByDateRange(from: LocalDate, to: LocalDate, limit: Int, afterId: UUID?): List<JournalEntry>

    suspend fun findByIdempotencyKey(idempotencyKey: String): JournalEntry?

    suspend fun nextEntryNumber(): Long

    /**
     * Persist a journal entry, optional idempotency key, and its outbox messages, atomically.
     * The list is JournalPosted + one AccountBookedChanged per affected customer account
     * (ADR-0039 Phase D) — all written in the same transaction as the journal.
     */
    suspend fun save(entry: JournalEntry, idempotencyKey: String?, outbox: List<OutboxMessage>): JournalEntry

    /**
     * Append standalone outbox messages (no journal entry) in one transaction — used by the
     * booked-change replay (ops recovery, issue #860) to re-emit historical events for a downstream
     * projection catch-up. Returns the number of messages enqueued.
     */
    suspend fun appendOutbox(messages: List<OutboxMessage>): Int

    /** Persist a reversal entry, flag the original as reversed, and enqueue its outbox messages, atomically. */
    suspend fun saveReversal(
        reversal: JournalEntry,
        originalId: UUID,
        originalEntryDate: LocalDate,
        outbox: List<OutboxMessage>,
    ): JournalEntry

    suspend fun trialBalance(asOf: LocalDate): List<TrialBalanceLine>

    /**
     * Per-GL-account debit/credit totals over POSTED journal lines whose entry_date falls in
     * [from, to] (inclusive) — the fiscal-period aggregation behind the entity-level year close
     * (ADR-0078 D5). Read-only; no schema involved beyond the existing journal tables.
     */
    suspend fun trialBalanceForPeriod(from: LocalDate, to: LocalDate): List<TrialBalanceLine>

    /**
     * Per-customer deposit-control sub-ledger balances as of a date, optionally for a single
     * customer account. Aggregates POSTED journal lines that carry a sub_account_id (ADR-0039
     * Phase B), grouped by (sub_account_id, base_currency).
     */
    suspend fun subLedgerBalances(asOf: LocalDate, subAccountId: UUID?): List<SubLedgerBalance>

    /**
     * Tie-out: compares the GL aggregate balance of [controlAccountId] against the sum of all
     * per-customer sub-ledger entries for that account as of [asOf].
     *
     * Returns one [ControlAccountTieOut] per currency found in the control account's journal
     * lines. An empty list means the account has no posted activity yet.
     * A non-zero [ControlAccountTieOut.delta] is an incident — every deposit-control line must
     * carry a sub_account_id; any that don't create a break (CNB zákon 563/1991 Sb.).
     */
    suspend fun controlAccountTieOut(controlAccountId: UUID, asOf: LocalDate): List<ControlAccountTieOut>
}

/** Outbound port for publishing ledger domain events to the broker. */
interface LedgerEventPublisher {

    suspend fun publish(topic: String, key: String, event: Any)
}
