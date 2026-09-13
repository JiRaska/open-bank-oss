// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import com.openbank.loyalty.domain.EarnCatalog
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * ADR-0282 D5's expiry, first-in-first-out. An expired lot releases the bank's obligation, so the
 * EXPIRE row is what the provisioning journal reads to release the provision — which is why expiry
 * is written to the ledger rather than inferred from a timestamp at read time.
 *
 * The sweep processes a bounded page of parties per run. It returns how many lots it expired so
 * the caller can record it; a run that expires nothing is a legitimate and common outcome, and is
 * reported as zero rather than as an absence.
 */
@ApplicationScoped
class ExpireLeavesUseCase(
    private val ledger: LeafLedgerRepository,
    private val metrics: LoyaltyMetricsPort,
    private val clock: Clock,
) {
    suspend fun sweep(batchSize: Int = DEFAULT_BATCH): Int {
        val now = Instant.now(clock)
        val parties = ledger.partiesWithExpirableLots(now, batchSize)
        var expired = 0
        for (partyId in parties) {
            expired += expireFor(partyId, now)
        }
        if (expired > 0) metrics.leavesExpired(expired)
        return expired
    }

    private suspend fun expireFor(partyId: UUID, now: Instant): Int {
        val lots = LeafLedger.expirableLots(ledger.entriesFor(partyId), now)
        if (lots.isEmpty()) return 0
        val entries = lots.map { lot ->
            LeafLedgerEntry(
                id = Ids.newId(),
                partyId = partyId,
                type = LeafEntryType.EXPIRE,
                leaves = lot.remaining,
                remaining = com.openbank.loyalty.domain.Leaves.ZERO,
                ruleVersion = EarnCatalog.RULE_VERSION,
                // The lot being released IS the durable thing this entry is about, so its id is
                // the correlation — never a fresh id that points at nothing.
                correlationEventId = lot.id,
                occurredAt = now,
            )
        }
        ledger.appendExpiries(entries, lots.map { it.id })
        return entries.size
    }

    private companion object {
        const val DEFAULT_BATCH = 200
    }
}
