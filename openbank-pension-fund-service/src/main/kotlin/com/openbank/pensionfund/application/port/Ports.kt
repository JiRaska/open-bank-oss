// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.port

import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.StrategyChange
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NotFoundException(message: String) : RuntimeException(message)

/**
 * Everything one use case changes, committed in ONE transaction. A NAV publication settles orders,
 * moves holdings, writes transactions and may spawn switch-in legs — a partial commit would leave
 * the register disagreeing with itself, so the store takes the whole change set or nothing.
 */
data class StoreChanges(
    val funds: List<Fund> = emptyList(),
    val strategies: List<FundStrategy> = emptyList(),
    val strategyChanges: List<StrategyChange> = emptyList(),
    val navs: List<NavRecord> = emptyList(),
    val orders: List<UnitOrder> = emptyList(),
    val transactions: List<UnitTransaction> = emptyList(),
    val holdings: List<UnitHolding> = emptyList(),
    /** Written once, with the NAV that was struck on them (#12425); never rewritten on publication. */
    val navPositions: List<NavPosition> = emptyList(),
)

// One read per query the use cases need; splitting it per aggregate would split the ONE commit too.
@Suppress("TooManyFunctions")
interface PensionFundStore {
    suspend fun commit(changes: StoreChanges)

    suspend fun fund(id: UUID): Fund?
    suspend fun funds(): List<Fund>

    suspend fun strategy(id: UUID): FundStrategy?
    suspend fun strategies(): List<FundStrategy>

    suspend fun strategyChange(id: UUID): StrategyChange?
    suspend fun strategyChanges(strategyId: UUID): List<StrategyChange>

    suspend fun nav(id: UUID): NavRecord?
    suspend fun navs(fundId: UUID): List<NavRecord>
    suspend fun latestPublishedNav(fundId: UUID): NavRecord?
    suspend fun publishedNav(fundId: UUID, valuationDate: LocalDate): NavRecord?

    suspend fun pendingOrders(fundId: UUID): List<UnitOrder>
    suspend fun pendingOrdersForContract(contractId: UUID): List<UnitOrder>
    suspend fun orders(contractId: UUID): List<UnitOrder>
    suspend fun orderByIdempotencyKey(contractId: UUID, idempotencyKey: String): UnitOrder?

    suspend fun holding(contractId: UUID, fundId: UUID): UnitHolding?
    suspend fun holdings(contractId: UUID): List<UnitHolding>
    suspend fun unitsOutstanding(fundId: UUID): BigDecimal

    suspend fun transactionsPricedAt(navId: UUID): List<UnitTransaction>
    suspend fun transactions(contractId: UUID): List<UnitTransaction>

    // ---- reporting reads (#12425): aggregate inputs, by fund and valuation date ----

    /** PUBLISHED NAVs of [fundId] valued on or before [upTo], oldest first. */
    suspend fun publishedNavsUpTo(fundId: UUID, upTo: LocalDate): List<NavRecord>

    /** Every unit transaction priced at one of [navIds]. */
    suspend fun transactionsPricedAtAny(navIds: Collection<UUID>): List<UnitTransaction>

    /** The positions [navId] was struck on. */
    suspend fun navPositions(navId: UUID): List<NavPosition>
}

/**
 * Prices for the instruments a fund holds. Treasury (ADR-0315) has no market-data adapter to reuse,
 * and its book is the BANK's — fund assets must never be valued through, or posted into, it. A
 * deployment binds this port to its market-data vendor; the shipped adapter is a stub.
 */
interface MarketPricePort {
    suspend fun price(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal?
}
