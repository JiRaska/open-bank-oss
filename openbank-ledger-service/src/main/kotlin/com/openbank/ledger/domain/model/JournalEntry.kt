// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class JournalEntry(
    val id: UUID,
    val entryNumber: Long?,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val valueDate: LocalDate,
    val description: String?,
    val status: JournalStatus,
    val lines: List<JournalLine>,
    val createdAt: Instant,
    val createdBy: UUID,
    val version: Long,
    val reversalOf: UUID? = null,
    /**
     * ADR-0252 taint: this entry was posted by a bank-owned synthetic (canary) customer, not a real
     * one. Assigned once, at posting, from the trusted request-scoped decision — never from a
     * caller-supplied field. It excludes the entry from the regulatory aggregates ([LedgerScope])
     * and from nothing else; the posting itself is a real double-entry posting in every other way.
     */
    val synthetic: Boolean = false,
) {
    init {
        requireValid(lines.size >= 2) { "Journal entry must have at least 2 lines" }
        validateBalance()
    }

    fun validateBalance() {
        // A correct multi-currency ledger must balance WITHIN each currency, not across a
        // base-currency sum (which hides FX gain/loss). Each GL account is single-currency, so
        // the line's settlement currency is its baseAmount.currency. Cross-currency economic
        // events self-balance per currency by routing through FX position accounts (ADR-0025).
        val currencies = lines.map { it.baseAmount.currency }.toSet()
        for (currency in currencies) {
            val debits = lines.filter { it.side == JournalSide.DEBIT && it.baseAmount.currency == currency }
                .sumOf { it.baseAmount.amount }
            val credits = lines.filter { it.side == JournalSide.CREDIT && it.baseAmount.currency == currency }
                .sumOf { it.baseAmount.amount }
            requireValid(debits.compareTo(credits) == 0) {
                "Journal entry is not balanced in ${currency.code}: debits=$debits credits=$credits"
            }
        }
    }

    fun post(): JournalEntry {
        checkConflict(status == JournalStatus.PENDING) { "Can only post PENDING journal entries, current: $status" }
        return copy(status = JournalStatus.POSTED)
    }

    /**
     * Per-customer-account booked deltas for the balance projection (ADR-0039 Phase D).
     *
     * Only deposit-control legs carry a customer dimension ([JournalLine.subAccountId]); all other
     * legs (cash-clearing, FX position, P&L) are excluded. For each (customer account, settlement
     * currency) the delta is **credit-positive**: deposit-control GL accounts are a LIABILITY to the
     * customer (credit-normal), so a CREDIT increases the customer's booked balance and a DEBIT
     * decreases it — the same sign convention as the deposit-control sub-ledger / trial balance.
     * Net-zero groups are dropped. A reversal entry already carries flipped sides (see [reverse]),
     * so calling this on a reversal yields the naturally-negated deltas with no special-casing.
     */
    fun bookedDeltas(): List<AccountBookedDelta> = lines.filter { it.subAccountId != null }
        .groupBy { it.subAccountId!! to it.baseAmount.currency.code }
        .mapNotNull { (key, ls) ->
            val (accountId, currency) = key
            val delta = ls.fold(java.math.BigDecimal.ZERO) { acc, l ->
                if (l.side == JournalSide.CREDIT) acc + l.baseAmount.amount else acc - l.baseAmount.amount
            }
            if (delta.signum() == 0) null else AccountBookedDelta(accountId, currency, delta)
        }

    fun reverse(reversalId: UUID, reversedBy: UUID, lineIdProvider: (UUID) -> UUID = { Ids.newId() }): JournalEntry {
        checkConflict(status == JournalStatus.POSTED) { "Can only reverse POSTED journal entries" }
        val reversalLines = lines.map { line ->
            line.copy(
                id = lineIdProvider(line.id),
                // Re-parent onto the reversal entry: leaving the original's journalId here is the
                // V10-era bug (persistLines attached the reversal's lines to the ORIGINAL, saving
                // the reversal with zero lines — unreadable on hydration). The V10 migration
                // (2026-07-02) was a one-time hardcoded-id repair for the data corrupted up to that
                // point; the code fix landed later, with #528. Any reversal booked in between
                // re-created the same corruption with new ids — see V13's generic repair (#527).
                journalId = reversalId,
                side = if (line.side == JournalSide.DEBIT) JournalSide.CREDIT else JournalSide.DEBIT,
            )
        }
        return JournalEntry(
            id = reversalId,
            entryNumber = null,
            transactionId = transactionId,
            entryDate = entryDate,
            valueDate = valueDate,
            description = "Reversal of entry $entryNumber",
            status = JournalStatus.POSTED,
            lines = reversalLines,
            // The reversal's booking time is assigned by the application layer from an injected
            // Clock (ADR-0100 Layer 1 — clock injection); the domain stays time-free and inherits
            // the original's timestamp as a deterministic placeholder until the caller overrides it.
            createdAt = createdAt,
            createdBy = reversedBy,
            version = 0L,
            reversalOf = id,
            // A reversal of a synthetic entry is itself synthetic. Dropping the taint here would
            // leave the compensating half inside the real population while the original sat
            // outside it — so a real-only trial balance would carry the reversal's legs alone and
            // be skewed by exactly the original's net, with debits and credits still agreeing
            // globally (the same shape as the #939 status-filter defect, which is why that class
            // of break is invisible to the balanced check).
            synthetic = synthetic,
        )
    }
}

data class JournalLine(
    val id: UUID,
    val journalId: UUID,
    val glAccountId: UUID,
    val side: JournalSide,
    val amount: Money,
    val fxRate: java.math.BigDecimal?,
    val baseAmount: Money,
    val sequence: Int,
    /**
     * Sub-ledger (analytická evidence) dimension: the customer account this line belongs to,
     * set ONLY on deposit-control legs (ADR-0039 Phase B). Lets the GL control account tie out
     * against the per-account sub-ledger required by CNB 563/1991 + decree 501/2002. Null on all
     * non-deposit-control legs (cash-clearing, FX position, P&L), which carry no customer dimension.
     */
    val subAccountId: UUID? = null,
)

enum class JournalStatus { PENDING, POSTED, REVERSED }
enum class JournalSide { DEBIT, CREDIT }

/**
 * A signed booked-balance change for one (customer account, currency), derived from a posted
 * journal's deposit-control legs (ADR-0039 Phase D). Credit-positive (see [JournalEntry.bookedDeltas]).
 */
data class AccountBookedDelta(val accountId: UUID, val currency: String, val delta: java.math.BigDecimal)
