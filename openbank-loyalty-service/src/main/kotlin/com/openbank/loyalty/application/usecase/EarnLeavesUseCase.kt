// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import com.openbank.loyalty.domain.AnnualCap
import com.openbank.loyalty.domain.EarnCatalog
import com.openbank.loyalty.domain.EarnOutcome
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * ADR-0282 D2/D3/D5 — mint Lístky for an achievement the platform has already observed.
 *
 * Three outcomes, three distinct return values, three distinct metrics. The one that matters is
 * [EarnOutcome.Capped]: it is neither an error nor a success, it means the annual cap refused the
 * award, and it must stay visible. A programme that silently stops rewarding looks identical to a
 * quiet one unless the refusal has its own signal.
 */
@ApplicationScoped
class EarnLeavesUseCase(
    private val ledger: LeafLedgerRepository,
    private val metrics: LoyaltyMetricsPort,
    private val clock: Clock,
) {
    suspend fun earn(partyId: UUID, source: LeafEarnSource, correlationEventId: UUID): EarnOutcome {
        val existing = ledger.findEarn(partyId, source, correlationEventId)
        if (existing != null) {
            metrics.earnReplayed(source.id)
            return EarnOutcome.AlreadyAwarded(existing)
        }

        val rule = EarnCatalog.ruleFor(source)
        val now = Instant.now(clock)
        val earnedThisYear = ledger.earnedInYearOf(partyId, now)

        return when (val decision = AnnualCap.evaluate(earnedThisYear, rule.leaves)) {
            is AnnualCap.Decision.Capped -> {
                metrics.earnCapped(source.id, rule.leaves.value)
                EarnOutcome.Capped(requested = rule.leaves, remaining = decision.remaining)
            }
            AnnualCap.Decision.Grant -> {
                val entry = newLot(partyId, source, rule.leaves, correlationEventId, now, rule.validity)
                ledger.appendEarn(entry)
                metrics.earnAwarded(source.id, rule.leaves.value)
                EarnOutcome.Awarded(entry)
            }
        }
    }

    private fun newLot(
        partyId: UUID,
        source: LeafEarnSource,
        leaves: Leaves,
        correlationEventId: UUID,
        now: Instant,
        validity: java.time.Duration,
    ) = LeafLedgerEntry(
        id = Ids.newId(),
        partyId = partyId,
        type = LeafEntryType.EARN,
        leaves = leaves,
        remaining = leaves,
        earnSource = source,
        ruleVersion = EarnCatalog.RULE_VERSION,
        correlationEventId = correlationEventId,
        occurredAt = now,
        expiresAt = now.plus(validity),
    )
}
