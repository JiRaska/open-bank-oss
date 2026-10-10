// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavCalculator
import com.openbank.pensionfund.domain.model.NavInput
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.PricedPosition
import com.openbank.pensionfund.domain.model.TransactionCorrection
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

data class NavPublication(val nav: NavRecord, val settledOrders: Int, val corrections: List<TransactionCorrection>)

/**
 * NAV lifecycle: calculate (maker) → publish (checker). Publishing an ordinary NAV settles every
 * order queued for the fund before that moment; publishing a CORRECTION supersedes the original
 * and re-prices every transaction that settled at it, adjusting holdings by the difference.
 */
@ApplicationScoped
class NavService(private val store: PensionFundStore, private val prices: MarketPricePort, private val clock: Clock) {
    suspend fun calculate(fundId: UUID, request: NavCalculationRequest, actor: String): NavRecord {
        val fund = store.fund(fundId) ?: throw NotFoundException("fund $fundId not found")
        check(fund.status == FundStatus.ACTIVE) { "fund $fundId is closed" }
        require(request.cash.signum() >= 0) { "cash must not be negative" }
        check(
            store.navs(fundId).none {
                it.status == NavStatus.CALCULATED && it.valuationDate == request.valuationDate
            },
        ) {
            "a NAV for ${request.valuationDate} is already awaiting approval"
        }
        val original = store.publishedNav(fundId, request.valuationDate)
        val previous = store.navs(fundId)
            .filter { it.status == NavStatus.PUBLISHED && it.valuationDate.isBefore(request.valuationDate) }
            .maxByOrNull { it.valuationDate }
        val accrualDays =
            previous?.let { ChronoUnit.DAYS.between(it.valuationDate, request.valuationDate).toInt() } ?: 1

        val positions = request.positions.map { line ->
            val price = line.price
                ?: prices.price(line.instrumentId, request.valuationDate, fund.currency)
                ?: throw IllegalArgumentException(
                    "no market price for ${line.instrumentId} on ${request.valuationDate}",
                )
            PricedPosition(line.instrumentId, line.quantity, price)
        }
        // A correction values the SAME units the original did: the units outstanding at that date
        // are the ones the original NAV priced, not whatever the register holds today.
        val units = original?.figures?.unitsOutstanding ?: store.unitsOutstanding(fundId)
        val figures = NavCalculator.calculate(
            NavInput(
                positions = positions,
                cash = request.cash,
                otherLiabilities = request.otherLiabilities,
                unitsOutstanding = units,
                managementFeeRate = fund.managementFeeRate,
                accrualDays = accrualDays,
                launchNavPerUnit = fund.launchNavPerUnit,
            ),
        )
        val nav = NavRecord(
            id = Ids.newId(),
            fundId = fundId,
            valuationDate = request.valuationDate,
            figures = figures,
            status = NavStatus.CALCULATED,
            calculatedBy = actor,
            calculatedAt = clock.instant(),
            correctsNavId = original?.id,
        )
        store.commit(StoreChanges(navs = listOf(nav)))
        return nav
    }

    suspend fun nav(id: UUID): NavRecord = store.nav(id) ?: throw NotFoundException("NAV $id not found")

    suspend fun navs(fundId: UUID): List<NavRecord> = store.navs(fundId)

    suspend fun reject(id: UUID, actor: String): NavRecord {
        val rejected = nav(id).reject(actor)
        store.commit(StoreChanges(navs = listOf(rejected)))
        return rejected
    }

    suspend fun publish(id: UUID, actor: String): NavPublication {
        val published = nav(id).publish(actor, clock.instant())
        return if (published.isCorrection) publishCorrection(published) else publishAndSettle(published)
    }

    private suspend fun publishAndSettle(nav: NavRecord): NavPublication {
        val pending = store.pendingOrders(nav.fundId).filter { it.placedAt.isBefore(nav.publishedAt) }
        val holdings = mutableMapOf<UUID, UnitHolding>()
        val orders = mutableListOf<UnitOrder>()
        val transactions = mutableListOf<UnitTransaction>()
        // Oldest first, and money in before money out — a redemption ordered after a subscription
        // in the same window must be able to sell the units that subscription bought.
        pending.sortedWith(compareBy<UnitOrder>({ it.isOutgoing }, { it.placedAt })).forEach { order ->
            val holding = holdings[order.contractId]
                ?: store.holding(order.contractId, order.fundId)
                ?: UnitHolding(order.contractId, order.fundId, BigDecimal.ZERO.setScale(Precision.UNIT_SCALE))
            val settlement = ForwardPricer.settle(order, nav, holding, Ids.newId(), Ids.newId())
            holdings[order.contractId] = settlement.holding
            orders += settlement.order
            settlement.followUp?.let { orders += it }
            transactions += settlement.transaction
        }
        store.commit(
            StoreChanges(
                navs = listOf(nav),
                orders = orders,
                transactions = transactions,
                holdings = holdings.values.toList(),
            ),
        )
        return NavPublication(nav, pending.size, emptyList())
    }

    private suspend fun publishCorrection(correction: NavRecord): NavPublication {
        val originalId = checkNotNull(correction.correctsNavId)
        val original = nav(originalId)
        check(original.status == NavStatus.PUBLISHED) { "NAV $originalId is no longer the published one" }
        val corrections = store.transactionsPricedAt(originalId).map { ForwardPricer.reprice(it, correction) }
        val holdings = mutableMapOf<Pair<UUID, UUID>, UnitHolding>()
        corrections.filter { it.unitsDelta.signum() != 0 }.forEach { c ->
            val key = c.transaction.contractId to c.transaction.fundId
            val holding = holdings[key] ?: checkNotNull(store.holding(key.first, key.second)) {
                "transaction ${c.transaction.id} has no holding to correct"
            }
            holdings[key] = holding.plus(c.unitsDelta)
        }
        store.commit(
            StoreChanges(
                navs = listOf(original.supersede(), correction),
                transactions = corrections.map { it.transaction },
                holdings = holdings.values.toList(),
            ),
        )
        return NavPublication(correction, 0, corrections)
    }
}
