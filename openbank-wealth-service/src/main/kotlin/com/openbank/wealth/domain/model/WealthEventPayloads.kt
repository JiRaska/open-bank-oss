// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One data class per event type, and that is not verbosity.
 *
 * The ADR-0006 contract-agreement gate pairs each AsyncAPI message with the data class whose
 * companion declares that `EVENT_TYPE`, then compares the documented properties against the
 * CONSTRUCTOR properties. A shared "envelope plus map" would leave the contract unchecked in both
 * directions: a field could be added and never documented, or documented and never sent, and CI
 * would agree with both. Add an event here AND a message in
 * `openbank-contracts/openbank-wealth-service/asyncapi.yaml`.
 *
 * What these payloads deliberately do NOT carry: the holding's [DeclaredHolding.label],
 * [DeclaredHolding.externalReference] and [DeclaredHolding.documentIds]. ADR-0301 D7 limits every
 * downstream use to counts and totals, so the itemised description of what a customer owns never
 * reaches a topic other services consume. A segment rule needs the type and the amount; it has no
 * business knowing the watch is a Speedmaster.
 */
data class HoldingDeclared(
    val holdingId: UUID,
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val amount: BigDecimal,
    val currency: String,
    val valuedAt: LocalDate,
    val valuationSource: ValuationSource,
    val ownershipShare: BigDecimal,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "wealth.holding.declared.v1"
    }
}

data class HoldingRevalued(
    val holdingId: UUID,
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val amount: BigDecimal,
    val currency: String,
    val valuedAt: LocalDate,
    val valuationSource: ValuationSource,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "wealth.holding.revalued.v1"
    }
}

data class HoldingWithdrawn(
    val holdingId: UUID,
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "wealth.holding.withdrawn.v1"
    }
}
