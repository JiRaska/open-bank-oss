// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pensionfund.application.port.MarketPricePort
import com.openbank.pensionfund.application.port.NavEvent
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundMetrics
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.PriceLookupOutcome
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.NavCalculator
import com.openbank.pensionfund.domain.model.NavInput
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.PricedPosition
import com.openbank.pensionfund.domain.model.TransactionCorrection
import com.openbank.pensionfund.domain.model.UnitHolding
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.pensionfund.domain.model.UnitTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

data class NavPublication(val nav: NavRecord, val settledOrders: Int, val corrections: List<TransactionCorrection>)

/**
 * NAV lifecycle: calculate (maker) → publish (checker). Publishing an ordinary NAV settles every
 * order queued for the fund before that moment; publishing a CORRECTION supersedes the original
 * and re-prices every transaction that settled at it, adjusting holdings by the difference.
 */
@ApplicationScoped
class NavService(
    private val store: PensionFundStore,
    private val prices: MarketPricePort,
    private val clock: Clock,
    private val metrics: PensionFundMetrics,
) {
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
                ?: lookUpPrice(line.instrumentId, request.valuationDate, fund.currency)
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
            positionsRecorded = true,
        )
        store.commit(
            StoreChanges(
                navs = listOf(nav),
                navPositions = positions.map { NavPosition(nav.id, it.instrumentId, it.quantity, it.price) },
            ),
        )
        metrics.navEvent(fund.isin, NavEvent.CALCULATED, nav.isCorrection)
        return nav
    }

    /** One port lookup, counted by outcome; a throw is counted and rethrown, never swallowed. */
    @Suppress("TooGenericExceptionCaught") // any port failure is FAILED; it is rethrown unchanged
    private suspend fun lookUpPrice(instrumentId: String, valuationDate: LocalDate, currency: String): BigDecimal? {
        val price = try {
            prices.price(instrumentId, valuationDate, currency)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            metrics.marketPriceLookup(PriceLookupOutcome.FAILED)
            throw e
        }
        metrics.marketPriceLookup(if (price == null) PriceLookupOutcome.MISSING else PriceLookupOutcome.FOUND)
        return price
    }

    private suspend fun isin(fundId: UUID): String =
        store.fund(fundId)?.isin ?: throw NotFoundException("fund $fundId not found")

    suspend fun nav(id: UUID): NavRecord = store.nav(id) ?: throw NotFoundException("NAV $id not found")

    suspend fun navs(fundId: UUID): List<NavRecord> = store.navs(fundId)

    suspend fun reject(id: UUID, actor: String): NavRecord {
        val rejected = nav(id).reject(actor)
        store.commit(StoreChanges(navs = listOf(rejected)))
        metrics.navEvent(isin(rejected.fundId), NavEvent.REJECTED, rejected.isCorrection)
        return rejected
    }

    suspend fun publish(id: UUID, actor: String): NavPublication {
        val published = nav(id).publish(actor, clock.instant())
        val fund = store.fund(published.fundId) ?: throw NotFoundException("fund ${published.fundId} not found")
        val isin = fund.isin
        val publication =
            if (published.isCorrection) {
                publishCorrection(
                    published,
                )
            } else {
                publishAndSettle(published, isin, fund.currency)
            }
        // Counted only after the commit returned: a publication that rolled back published nothing.
        metrics.navEvent(isin, NavEvent.PUBLISHED, published.isCorrection)
        if (published.isCorrection) {
            metrics.navCorrectionRepriced(isin, publication.corrections.size)
        } else {
            val dayEnded = published.valuationDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            metrics.navPublicationLag(isin, Duration.between(dayEnded, checkNotNull(published.publishedAt)))
        }
        return publication
    }

    private suspend fun publishAndSettle(nav: NavRecord, isin: String, currency: String): NavPublication {
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
        orders.filter { it.status == OrderStatus.SETTLED }.forEach { metrics.order(isin, it.type, OrderStatus.SETTLED) }
        // A switch-in leg is a new PENDING order of the TARGET fund, counted under that fund.
        orders.filter {
            it.status == OrderStatus.PENDING
        }.forEach { metrics.order(isin(it.fundId), it.type, OrderStatus.PENDING) }
        transactions.forEach { tx ->
            pending.firstOrNull {
                it.id == tx.orderId
            }?.let { metrics.settledAmount(isin, it.type, tx.amount, currency) }
        }
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
