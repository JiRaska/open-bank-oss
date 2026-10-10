// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class HoldingValuation(
    val fundId: UUID,
    val units: BigDecimal,
    val navPerUnit: BigDecimal?,
    val navDate: LocalDate?,
    val value: BigDecimal?,
    val currency: String,
)

data class ContractValuation(
    val contractId: UUID,
    val holdings: List<HoldingValuation>,
    val pendingOrders: List<UnitOrder>,
)

@ApplicationScoped
class UnitRegisterService(private val store: PensionFundStore, private val clock: Clock) {
    suspend fun place(command: PlaceOrderCommand): UnitOrder {
        require(command.type != OrderType.SWITCH_IN) {
            "SWITCH_IN is created by a settled switch, never placed directly"
        }
        val source = requireActiveFund(command.fundId)
        command.targetFundId?.let {
            val target = requireActiveFund(it)
            require(source.currency == target.currency) {
                "cross-currency switches are not supported: ${source.currency} to ${target.currency}"
            }
        }
        val order = UnitOrder(
            id = Ids.newId(),
            contractId = command.contractId,
            fundId = command.fundId,
            type = command.type,
            amount = command.amount?.let(Precision::money),
            units = command.units?.let(Precision::units),
            targetFundId = command.targetFundId,
            parentOrderId = null,
            status = OrderStatus.PENDING,
            placedAt = clock.instant(),
            idempotencyKey = command.idempotencyKey,
        )
        // A retry returns the ORIGINAL order; the same key carrying a different instruction is a
        // caller bug, refused rather than silently answered with an order it did not ask for.
        store.orderByIdempotencyKey(command.contractId, command.idempotencyKey)?.let { existing ->
            check(existing.sameInstructionAs(order)) { "Idempotency-Key reused for a different order" }
            return existing
        }
        if (order.isOutgoing) return store.reserveOutgoing(order)
        store.commit(StoreChanges(orders = listOf(order)))
        return order
    }

    suspend fun orders(contractId: UUID): List<UnitOrder> = store.orders(contractId)

    suspend fun transactions(contractId: UUID): List<UnitTransaction> = store.transactions(contractId)

    /** Holdings valued at each fund's latest PUBLISHED NAV; a fund with none yet has no value. */
    suspend fun valuation(contractId: UUID): ContractValuation {
        val holdings = store.holdings(contractId).map { holding ->
            val fund = store.fund(holding.fundId) ?: throw NotFoundException("fund ${holding.fundId} not found")
            val nav = store.latestPublishedNav(holding.fundId)
            HoldingValuation(
                fundId = holding.fundId,
                units = holding.units,
                navPerUnit = nav?.navPerUnit,
                navDate = nav?.valuationDate,
                value = nav?.let { Precision.proceeds(holding.units, it.navPerUnit) },
                currency = fund.currency,
            )
        }
        return ContractValuation(contractId, holdings, store.pendingOrdersForContract(contractId))
    }

    private suspend fun requireActiveFund(fundId: UUID): Fund {
        val fund = store.fund(fundId) ?: throw NotFoundException("fund $fundId not found")
        check(fund.status == FundStatus.ACTIVE) { "fund $fundId is closed" }
        return fund
    }
}
