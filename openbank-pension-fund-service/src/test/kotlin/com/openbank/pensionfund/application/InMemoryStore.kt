// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application

import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.StrategyChange
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class InMemoryStore : PensionFundStore {
    val funds = linkedMapOf<UUID, Fund>()
    val strategies = linkedMapOf<UUID, FundStrategy>()
    val changes = linkedMapOf<UUID, StrategyChange>()
    val navs = linkedMapOf<UUID, NavRecord>()
    val orders = linkedMapOf<UUID, UnitOrder>()
    val transactions = linkedMapOf<UUID, UnitTransaction>()
    val holdings = linkedMapOf<Pair<UUID, UUID>, UnitHolding>()

    private val reservationLock = Mutex()

    override suspend fun reserveOutgoing(order: UnitOrder): UnitOrder = reservationLock.withLock {
        require(order.isOutgoing && order.status == OrderStatus.PENDING)
        val existing = order.idempotencyKey?.let { orderByIdempotencyKey(order.contractId, it) }
        if (existing != null) {
            check(existing.sameInstructionAs(order)) { "Idempotency-Key reused for a different order" }
            existing
        } else {
            val held = holding(order.contractId, order.fundId)?.units ?: BigDecimal.ZERO
            val reserved = pendingOrders(order.fundId).filter { it.contractId == order.contractId && it.isOutgoing }
                .fold(BigDecimal.ZERO) { total, queued -> total + requireNotNull(queued.units) }
            check(held - reserved >= requireNotNull(order.units)) { "Insufficient unreserved units" }
            orders[order.id] = order
            order
        }
    }

    override suspend fun commit(changes: StoreChanges) {
        changes.funds.forEach { funds[it.id] = it }
        changes.strategies.forEach { strategies[it.id] = it }
        changes.strategyChanges.forEach { this.changes[it.id] = it }
        changes.navs.forEach { navs[it.id] = it }
        changes.orders.forEach { orders[it.id] = it }
        changes.transactions.forEach { transactions[it.id] = it }
        changes.holdings.forEach { holdings[it.contractId to it.fundId] = it }
    }

    override suspend fun fund(id: UUID) = funds[id]
    override suspend fun funds() = funds.values.toList()
    override suspend fun strategy(id: UUID) = strategies[id]
    override suspend fun strategies() = strategies.values.toList()
    override suspend fun strategyChange(id: UUID) = changes[id]
    override suspend fun strategyChanges(strategyId: UUID) = changes.values.filter { it.strategyId == strategyId }
    override suspend fun nav(id: UUID) = navs[id]
    override suspend fun navs(fundId: UUID) = navs.values.filter { it.fundId == fundId }
    override suspend fun latestPublishedNav(fundId: UUID) =
        navs.values.filter { it.fundId == fundId && it.status == NavStatus.PUBLISHED }.maxByOrNull { it.valuationDate }
    override suspend fun publishedNav(fundId: UUID, valuationDate: LocalDate) = navs.values.firstOrNull {
        it.fundId == fundId &&
            it.valuationDate == valuationDate &&
            it.status == NavStatus.PUBLISHED
    }
    override suspend fun pendingOrders(fundId: UUID) = orders.values.filter {
        it.fundId == fundId &&
            it.status == OrderStatus.PENDING
    }
    override suspend fun pendingOrdersForContract(contractId: UUID) =
        orders.values.filter { it.contractId == contractId && it.status == OrderStatus.PENDING }
    override suspend fun orders(contractId: UUID) = orders.values.filter { it.contractId == contractId }
    override suspend fun orderByIdempotencyKey(contractId: UUID, idempotencyKey: String) =
        orders.values.firstOrNull { it.contractId == contractId && it.idempotencyKey == idempotencyKey }
    override suspend fun holding(contractId: UUID, fundId: UUID) = holdings[contractId to fundId]
    override suspend fun holdings(contractId: UUID) = holdings.values.filter { it.contractId == contractId }
    override suspend fun unitsOutstanding(fundId: UUID): BigDecimal =
        holdings.values.filter { it.fundId == fundId }.fold(BigDecimal.ZERO) { a, h -> a + h.units }
    override suspend fun transactionsPricedAt(navId: UUID) = transactions.values.filter { it.navId == navId }
    override suspend fun transactions(contractId: UUID) = transactions.values.filter { it.contractId == contractId }
}
