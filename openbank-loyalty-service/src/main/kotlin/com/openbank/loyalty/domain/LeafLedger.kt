// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import java.time.Instant
import java.util.UUID

/**
 * The pure FIFO resolution rules over a party's EARN lots. No persistence, no clock, no framework
 * — the application layer supplies the lots and the instant, so every rule here is testable
 * without a database.
 */
object LeafLedger {

    /** Spendable balance: what is left on lots that have neither been spent nor expired. */
    fun balance(lots: List<LeafLedgerEntry>, at: Instant): Leaves =
        spendableLots(lots, at).fold(Leaves.ZERO) { acc, lot -> acc + lot.remaining }

    /** EARN lots with something left on them, oldest first — the order a burn consumes them in. */
    fun spendableLots(lots: List<LeafLedgerEntry>, at: Instant): List<LeafLedgerEntry> = lots
        .filter { it.type == LeafEntryType.EARN && !it.remaining.isZero() && !it.isExpiredAt(at) }
        .sortedBy { it.occurredAt }

    /** EARN lots whose expiry has passed while they still held value. */
    fun expirableLots(lots: List<LeafLedgerEntry>, at: Instant): List<LeafLedgerEntry> = lots
        .filter { it.type == LeafEntryType.EARN && !it.remaining.isZero() && it.isExpiredAt(at) }
        .sortedBy { it.occurredAt }

    /**
     * Resolves which lots pay for [amount], oldest first.
     *
     * Returns [Allocation.Insufficient] rather than throwing or partially allocating: a redemption
     * a party cannot afford is an ordinary outcome of the API, not an error condition, and the
     * caller needs the shortfall to explain it.
     */
    fun allocate(lots: List<LeafLedgerEntry>, amount: Leaves, at: Instant): Allocation {
        require(!amount.isZero()) { "cannot allocate zero leaves" }
        val spendable = spendableLots(lots, at)
        val available = spendable.fold(Leaves.ZERO) { acc, lot -> acc + lot.remaining }
        if (available < amount) return Allocation.Insufficient(available)

        var outstanding = amount
        val taken = mutableListOf<LotDebit>()
        for (lot in spendable) {
            if (outstanding.isZero()) break
            val take = if (lot.remaining <= outstanding) lot.remaining else outstanding
            taken += LotDebit(lot.id, take)
            outstanding -= take
        }
        return Allocation.Resolved(taken)
    }

    /** One lot and the amount taken from it. */
    data class LotDebit(val lotId: UUID, val amount: Leaves)

    sealed class Allocation {
        data class Resolved(val debits: List<LotDebit>) : Allocation()

        /** [available] is what the party actually had — the shortfall the caller reports back. */
        data class Insufficient(val available: Leaves) : Allocation()
    }
}
