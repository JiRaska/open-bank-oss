// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.ledger.domain.model.AccountBookedDelta
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.libs.domain.identifiers.Ids
import java.math.BigDecimal
import java.util.UUID

/**
 * In-memory general ledger over the **real `openbank-ledger-service` `JournalEntry` aggregate**
 * (not a re-model). Posting therefore runs the production `validateBalance()` invariant on
 * construction, reversal runs the production `reverse()`, and the balance projection consumes
 * the production `bookedDeltas()` (ADR-0039 credit-positive deposit-control deltas). `post` is
 * idempotent on a caller-supplied key, exactly like `LedgerService.postJournal`
 * (`findByIdempotencyKey`) — the property the DST idempotency invariant relies on.
 */
class LedgerState {

    private val posted = mutableListOf<JournalEntry>()
    private val byKey = mutableMapOf<String, JournalEntry>()

    /** Post [entry] under [idempotencyKey]; a replay returns the original (no double-post). */
    fun post(idempotencyKey: String, entry: JournalEntry): JournalEntry {
        byKey[idempotencyKey]?.let { return it }
        posted.add(entry)
        byKey[idempotencyKey] = entry
        return entry
    }

    /**
     * Reverse a previously-posted entry via the real `JournalEntry.reverse`. Idempotent on the
     * original id, so a double-compensation (retry / FSM re-entry) can never double-reverse.
     */
    fun reverse(
        originalId: UUID,
        reversalId: UUID,
        reversedBy: UUID,
        lineIdProvider: (UUID) -> UUID = { Ids.newId() },
    ): JournalEntry {
        val reversalKey = "reversal-of-$originalId"
        byKey[reversalKey]?.let { return it }
        val original = posted.first { it.id == originalId }
        val reversal = original.reverse(reversalId, reversedBy, lineIdProvider)
        posted.add(reversal)
        byKey[reversalKey] = reversal
        return reversal
    }

    fun postedCount(): Int = posted.size

    /** Net credit-positive customer movement per `(account, currency)` — real `bookedDeltas()`. */
    fun netDeltas(): Map<AccountCurrency, BigDecimal> {
        val acc = mutableMapOf<AccountCurrency, BigDecimal>()
        posted.forEach { entry ->
            entry.bookedDeltas().forEach { delta: AccountBookedDelta ->
                acc.merge(AccountCurrency(delta.accountId, delta.currency), delta.delta, BigDecimal::add)
            }
        }
        return acc
    }

    /**
     * Full per-currency net of EVERY posted line (`Σ credit − Σ debit` on `baseAmount`). Each
     * entry self-balances per currency (the real `validateBalance`), so this must be zero in
     * every currency — the conservation-of-money invariant, checked across all entries.
     */
    fun lineNetByCurrency(): Map<String, BigDecimal> {
        val acc = mutableMapOf<String, BigDecimal>()
        posted.forEach { entry ->
            entry.lines.forEach { line ->
                val signed =
                    if (line.side == JournalSide.CREDIT) line.baseAmount.amount else line.baseAmount.amount.negate()
                acc.merge(line.baseAmount.currency.code, signed, BigDecimal::add)
            }
        }
        return acc
    }
}
