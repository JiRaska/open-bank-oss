// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The read model behind both the app's tree and Customer 360's Lípa panel (ADR-0282 D8's
 * reciprocal transparency: the two surfaces render the same source, so they cannot drift into
 * showing a customer something different from what an operator sees).
 */
@ApplicationScoped
class ReadLeafSummaryUseCase(private val ledger: LeafLedgerRepository, private val clock: Clock) {
    suspend fun summarise(partyId: UUID): LeafSummary {
        val now = Instant.now(clock)
        val entries = ledger.entriesFor(partyId)
        val spendable = LeafLedger.spendableLots(entries, now)
        return LeafSummary(
            partyId = partyId,
            balance = LeafLedger.balance(entries, now),
            earnedThisYear = ledger.earnedInYearOf(partyId, now),
            nextExpiry = spendable.minByOrNull { it.expiresAt ?: Instant.MAX }?.expiresAt,
            history = entries.sortedByDescending { it.occurredAt },
        )
    }

    /**
     * @param nextExpiry when the oldest still-spendable lot expires, or null when nothing is
     *   spendable. Surfaced so the app can show the expiry before it happens — ADR-0282 D5 makes
     *   that a property of the programme, not a courtesy.
     */
    data class LeafSummary(
        val partyId: UUID,
        val balance: Leaves,
        val earnedThisYear: Leaves,
        val nextExpiry: Instant?,
        val history: List<LeafLedgerEntry>,
    ) {
        fun earnedTotal(): Leaves = history
            .filter { it.type == LeafEntryType.EARN }
            .fold(Leaves.ZERO) { acc, e -> acc + e.leaves }
    }
}
