// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.application.port.`in`

import com.openbank.wealth.application.port.out.RecordedValuation
import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingType
import com.openbank.wealth.domain.model.Valuation
import java.math.BigDecimal
import java.util.UUID

/**
 * Declare a holding.
 *
 * [externalReference] is the caller's own stable handle for the thing — a cadastre number, a
 * VIN, an ISIN, a certificate id. It is the third component of the natural key that makes a
 * retried POST a no-op; a caller with nothing stable to name leaves it null and accepts that a
 * retry creates a second row.
 */
data class DeclareHoldingCommand(
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val label: String,
    val valuation: Valuation,
    val ownershipShare: BigDecimal = BigDecimal.ONE,
    val externalReference: String? = null,
    val documentIds: List<UUID> = emptyList(),
)

data class RevalueHoldingCommand(val holdingId: UUID, val valuation: Valuation)

interface DeclaredHoldingUseCase {
    /**
     * Idempotent on the natural key `(ownerPartyId, holdingType, externalReference)` when the
     * caller supplies a reference: a replay returns the existing holding untouched rather than
     * creating a second one.
     *
     * Note this is a design choice, not a gate requirement. `check-idempotency-coverage.py`
     * scopes its hard rule to `rules.yaml: money_path_services`, and wealth-service is not one,
     * so no CI gate would have noticed either way.
     */
    suspend fun declare(command: DeclareHoldingCommand): DeclaredHolding

    suspend fun revalue(command: RevalueHoldingCommand): DeclaredHolding

    /** Refused while the holding is pledged as lending collateral (ADR-0301 D3). */
    suspend fun withdraw(holdingId: UUID): DeclaredHolding

    suspend fun findById(holdingId: UUID): DeclaredHolding?

    /** Active and pledged holdings for one party; withdrawn rows are excluded. */
    suspend fun listForParty(ownerPartyId: UUID): List<DeclaredHolding>

    /** Every value ever asserted for this holding, newest first. Empty is impossible for a live holding. */
    suspend fun valuationHistory(holdingId: UUID): List<RecordedValuation>
}
