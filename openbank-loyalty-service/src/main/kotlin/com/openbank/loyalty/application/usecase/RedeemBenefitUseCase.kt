// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.loyalty.application.port.out.BenefitGrantRepository
import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import com.openbank.loyalty.domain.BenefitCatalog
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.BenefitGrantStatus
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.RedemptionOutcome
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * ADR-0282 D4 — burn Lístky for a catalogue benefit.
 *
 * What a [RedemptionOutcome.Granted] does and does not assert: the Lístky are burned and this
 * service has durably recorded, and published, that the benefit is owed. It does **not** assert
 * that billing, interest or fx has applied anything — those engines are money-path and are wired
 * in a later slice. `BenefitGrantStatus.GRANTED` is deliberately not called `APPLIED`.
 */
@ApplicationScoped
class RedeemBenefitUseCase(
    private val ledger: LeafLedgerRepository,
    private val grants: BenefitGrantRepository,
    private val metrics: LoyaltyMetricsPort,
    private val clock: Clock,
) {
    suspend fun redeem(partyId: UUID, benefitId: String, idempotencyKey: String): RedemptionOutcome {
        grants.findByIdempotencyKey(partyId, idempotencyKey)?.let {
            return RedemptionOutcome.AlreadyGranted(it)
        }

        val benefit = BenefitCatalog.find(benefitId) ?: run {
            metrics.benefitRefused(benefitId, REASON_UNKNOWN)
            return RedemptionOutcome.UnknownBenefit(benefitId)
        }

        val now = Instant.now(clock)
        val lots = ledger.entriesFor(partyId)
        val allocation = LeafLedger.allocate(lots, benefit.price, now)
        if (allocation is LeafLedger.Allocation.Insufficient) {
            metrics.benefitRefused(benefitId, REASON_INSUFFICIENT)
            return RedemptionOutcome.InsufficientLeaves(benefit.price, allocation.available)
        }

        val debits = (allocation as LeafLedger.Allocation.Resolved).debits
        val grant = BenefitGrant(
            id = Ids.newId(),
            partyId = partyId,
            benefitId = benefit.id,
            price = benefit.price,
            status = BenefitGrantStatus.GRANTED,
            idempotencyKey = idempotencyKey,
            reservedAt = now,
            grantedAt = now,
            expiresAt = now.plus(benefit.validity),
        )
        val burn = LeafLedgerEntry(
            id = Ids.newId(),
            partyId = partyId,
            type = LeafEntryType.BURN,
            leaves = benefit.price,
            remaining = com.openbank.loyalty.domain.Leaves.ZERO,
            benefitId = benefit.id,
            ruleVersion = com.openbank.loyalty.domain.EarnCatalog.RULE_VERSION,
            correlationEventId = grant.id,
            occurredAt = now,
        )
        ledger.appendBurnAndGrant(burn, debits, grant)
        metrics.benefitGranted(benefit.id, benefit.price.value)
        return RedemptionOutcome.Granted(grant)
    }

    private companion object {
        const val REASON_UNKNOWN = "unknown_benefit"
        const val REASON_INSUFFICIENT = "insufficient_leaves"
    }
}
