// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Identity of one participant's position in one fund — the holding key. */
data class ContractFund(val contractId: UUID, val fundId: UUID)

/**
 * Simulated pension-fund state (the unit register): every NAV the scenario struck through the
 * REAL `NavCalculator` and published under the REAL four-eyes rule, every order it placed, every
 * transaction the REAL `ForwardPricer` settled (a correction replaces the original in place,
 * mirroring the domain's `correctedFromNavId` audit trail), and the holding each (contract,
 * fund) pair currently holds — so the Layer-3 invariants can assert:
 *
 *  1. *order completeness* — no order may be left PENDING once a forward NAV exists to settle
 *     it (the order queues until the fund's NEXT published NAV; a pending order whose NAV never
 *     came is money parked without a price);
 *  2. *unit conservation* — the holding per (contract, fund) equals the fold of every
 *     transaction against that pair (subscribes/switch-ins credit units, redeems/switch-outs/
 *     fees debit them), so issued units that vanished (or appeared without a transaction)
 *     are caught, while the domain's own rounding (units issued rounded DOWN, fee units
 *     rounded UP) makes a naive re-computation off by design.
 */
class PensionFundBook {
    val navs = mutableListOf<NavRecord>()
    val orders = mutableListOf<UnitOrder>()
    val transactions = mutableListOf<UnitTransaction>()

    /** The funds this world trades — per-world state (never a companion: seeds must be independent). */
    val funds = mutableListOf<UUID>()

    private val holdings = mutableMapOf<ContractFund, UnitHolding>()

    fun holdingOf(key: ContractFund): UnitHolding = holdings.getOrDefault(
        key,
        UnitHolding(key.contractId, key.fundId, BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)),
    )

    fun recordHolding(holding: UnitHolding) {
        holdings[ContractFund(holding.contractId, holding.fundId)] = holding
    }

    fun holdingKeys(): Set<ContractFund> = holdings.keys

    /** Total units currently registered across all contracts — what a NAV's `unitsOutstanding` must equal. */
    fun unitsOutstanding(fundId: UUID): BigDecimal = holdings.filterKeys { it.fundId == fundId }.values
        .fold(BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)) { acc, h -> acc + h.units }

    fun latestPublishedNav(fundId: UUID): NavRecord? =
        navs.filter { it.fundId == fundId && it.status == NavStatus.PUBLISHED }
            .maxByOrNull { checkNotNull(it.publishedAt) }

    /** A forward NAV for [fundId] published strictly after [placedAt] — what a pending order may settle at. */
    fun settleableNav(fundId: UUID, placedAt: Instant): NavRecord? = navs.filter {
        it.fundId == fundId &&
            it.status == NavStatus.PUBLISHED &&
            checkNotNull(it.publishedAt).isAfter(placedAt)
    }.minByOrNull { checkNotNull(it.publishedAt) }

    fun pendingOrders(): List<UnitOrder> = orders.filter { it.status == OrderStatus.PENDING }

    fun replaceTransaction(original: UnitTransaction, corrected: UnitTransaction) {
        val index = transactions.indexOfFirst { it.id == original.id }
        check(index >= 0) { "transaction ${original.id} not in the book" }
        transactions[index] = corrected
    }
}
