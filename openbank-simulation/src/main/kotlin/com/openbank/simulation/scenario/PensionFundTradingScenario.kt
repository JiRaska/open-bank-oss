// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.pensionfund.domain.model.ForwardPricer
import com.openbank.pensionfund.domain.model.NavCalculator
import com.openbank.pensionfund.domain.model.NavInput
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.OrderStatus
import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.domain.model.Precision
import com.openbank.pensionfund.domain.model.PricedPosition
import com.openbank.pensionfund.domain.model.UnitOrder
import com.openbank.simulation.model.ContractFund
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * Test Intelligence simulation gap (money path): one step of the pension-fund unit register,
 * modelled directly against the **real `pension-fund-service` domain** — the `NavCalculator`
 * NAV computation (management-fee accrual Actual/365 on gross, launch price while no units are
 * outstanding), the four-eyes `NavRecord.publish` rule, and the `ForwardPricer` forward-pricing
 * settlement (an order queues until the fund's NEXT published NAV; a NAV that was already
 * published when the order was placed is refused as backward pricing) — not a re-model of any of
 * them, the same way [PensionLifecycleScenario] builds on the real `pension-service` aggregates
 * (ADR-0100). The domain packages are framework-free (ADR-0002).
 *
 * A step mirrors the production ladder: place orders against the register (SUBSCRIBE while the
 * contract holds nothing, REDEEM / SWITCH_OUT once it holds units — a switch spawns the
 * SWITCH_IN leg the REAL pricer derives), strike and publish the day's NAV for one fund through
 * the REAL calculator (superseding its previous published NAV, approving under a different
 * identity so the four-eyes rule is what publishes, not the scenario), then settle every order
 * whose fund now has a forward NAV. An injected write fault at settlement defers the order — it
 * stays PENDING with a settleable NAV, which the order-completeness invariant accepts as
 * legitimately deferred, and a later published NAV settles it. Occasionally the scenario
 * charges a management fee by cancelling units (rounded UP by the REAL `Precision`, so the fund
 * is never under-funded by its own fee) or re-prices a settled transaction at a corrected NAV
 * (the original NAV is SUPERSEDED, never edited), applying the unit delta to the holding.
 */
object PensionFundTradingScenario {

    private const val FUND_COUNT = 2
    private const val MIN_SUBSCRIBE_MINOR = 100_000L // 1 000.00 CZK
    private const val MAX_SUBSCRIBE_MINOR = 5_000_000L // 50 000.00 CZK
    private const val MIN_FEE_MINOR = 1_000L // 10.00 CZK
    private const val MAX_FEE_MINOR = 10_000L // 100.00 CZK
    private const val MANAGEMENT_FEE_RATE_PERCENT = 1
    private const val MAX_ACCRUAL_DAYS = 7
    private const val MAX_POSITIONS = 3
    private const val INSTRUMENT_SPACE = 100
    private const val MAX_POSITION_QUANTITY = 1_000L
    private const val MAX_POSITION_PRICE_MINOR = 10_000_000L // 100 000.00 CZK
    private const val FUND_CASH_MINOR = 100_000_000L // 1 000 000.00 CZK
    private const val CALCULATOR_IDENTITY = "nav-calculator"
    private const val APPROVER_IDENTITY = "nav-approver"
    private const val LAUNCH_NAV_PER_UNIT = "1000.000000"
    private const val FAIR_CHANCE = 0.5

    fun step(world: World) {
        val book = world.pensionFundBook
        val random = world.context.random
        if (book.funds.isEmpty()) {
            repeat(FUND_COUNT) { book.funds.add(random.nextUuid()) }
        }

        // Advance one simulated day so this step's NAV is strictly after anything placed earlier.
        world.context.clock.advance(Duration.ofDays(1))
        val placedAt = world.context.clock.instant()
        placeOrders(world, placedAt)

        // One hour later the valuation desk publishes the day's NAV for EVERY fund — a fund left
        // un-priced would leave its orders parked without a forward NAV, the exact defect the
        // order-completeness invariant exists to surface.
        world.context.clock.advance(Duration.ofHours(1))
        val publishedAt = world.context.clock.instant()
        val valuationDate = publishedAt.atZone(ZoneOffset.UTC).toLocalDate()
        val publishedFunds = publishNavs(world, valuationDate, publishedAt)

        settlePending(world)
        maybeChargeFee(world, publishedFunds)
        maybeCorrect(world, valuationDate, publishedAt)
    }

    private fun placeOrders(world: World, placedAt: java.time.Instant) {
        val book = world.pensionFundBook
        val random = world.context.random
        val contractId = world.customerAccounts[random.nextInt(world.customerAccounts.size)]

        val holdings = book.holdingKeys()
            .filter { it.contractId == contractId && book.holdingOf(it).units.signum() > 0 }
        if (holdings.isEmpty() || random.chance(FAIR_CHANCE)) {
            placeSubscribeOrder(world, placedAt, contractId)
        } else {
            placeOutgoingOrder(world, placedAt, contractId, holdings[random.nextInt(holdings.size)])
        }
    }

    private fun placeSubscribeOrder(world: World, placedAt: java.time.Instant, contractId: UUID) {
        val book = world.pensionFundBook
        val random = world.context.random
        book.orders.add(
            UnitOrder(
                id = random.nextUuid(),
                contractId = contractId,
                fundId = book.funds[random.nextInt(book.funds.size)],
                type = OrderType.SUBSCRIBE,
                amount = minorAmount(random.nextLong(MIN_SUBSCRIBE_MINOR, MAX_SUBSCRIBE_MINOR)),
                units = null,
                targetFundId = null,
                parentOrderId = null,
                status = OrderStatus.PENDING,
                placedAt = placedAt,
                idempotencyKey = "pf-sub-${random.nextUuid()}",
            ),
        )
    }

    private fun placeOutgoingOrder(world: World, placedAt: java.time.Instant, contractId: UUID, key: ContractFund) {
        val book = world.pensionFundBook
        val random = world.context.random
        // Up to half the holding, rounded DOWN (the register never moves fractional units
        // it cannot honour). A dust holding can round to zero — that is a subscribe step
        // instead (a zero-unit order is refused by the REAL aggregate's init).
        val units = Precision.units(
            book.holdingOf(key).units.divide(BigDecimal(2), Precision.UNIT_SCALE, RoundingMode.DOWN),
        )
        if (units.signum() <= 0) {
            placeSubscribeOrder(world, placedAt, contractId)
            return
        }
        if (random.chance(FAIR_CHANCE)) {
            book.orders.add(
                UnitOrder(
                    id = random.nextUuid(),
                    contractId = contractId,
                    fundId = key.fundId,
                    type = OrderType.REDEEM,
                    amount = null,
                    units = units,
                    targetFundId = null,
                    parentOrderId = null,
                    status = OrderStatus.PENDING,
                    placedAt = placedAt,
                    idempotencyKey = "pf-red-${random.nextUuid()}",
                ),
            )
        } else {
            // A switch names a different fund; with two funds that is simply the other one.
            val targetFundId = book.funds.single { it != key.fundId }
            book.orders.add(
                UnitOrder(
                    id = random.nextUuid(),
                    contractId = contractId,
                    fundId = key.fundId,
                    type = OrderType.SWITCH_OUT,
                    amount = null,
                    units = units,
                    targetFundId = targetFundId,
                    parentOrderId = null,
                    status = OrderStatus.PENDING,
                    placedAt = placedAt,
                    idempotencyKey = "pf-swo-${random.nextUuid()}",
                ),
            )
        }
    }

    /** Strike and publish each fund's NAV with the REAL calculator under the REAL four-eyes rule. */
    private fun publishNavs(
        world: World,
        valuationDate: java.time.LocalDate,
        publishedAt: java.time.Instant,
    ): List<UUID> {
        val book = world.pensionFundBook
        return book.funds.map { fundId ->
            publishNav(world, fundId, valuationDate, publishedAt)
        }
    }

    /** Strike the fund's NAV with the REAL calculator and publish it under the REAL four-eyes rule. */
    private fun publishNav(
        world: World,
        fundId: UUID,
        valuationDate: java.time.LocalDate,
        publishedAt: java.time.Instant,
    ): UUID {
        val book = world.pensionFundBook
        val random = world.context.random

        val positions = (1..1 + random.nextInt(MAX_POSITIONS)).map {
            PricedPosition(
                instrumentId = "INSTR-${random.nextInt(INSTRUMENT_SPACE)}",
                quantity = BigDecimal(random.nextLong(1, MAX_POSITION_QUANTITY)),
                price = minorAmount(random.nextLong(1, MAX_POSITION_PRICE_MINOR)),
            )
        }
        val figures = NavCalculator.calculate(
            NavInput(
                positions = positions,
                cash = minorAmount(FUND_CASH_MINOR),
                otherLiabilities = BigDecimal.ZERO.setScale(Precision.MONEY_SCALE),
                unitsOutstanding = book.unitsOutstanding(fundId),
                managementFeeRate = BigDecimal(MANAGEMENT_FEE_RATE_PERCENT).movePointLeft(2),
                accrualDays = 1 + random.nextInt(MAX_ACCRUAL_DAYS),
                launchNavPerUnit = BigDecimal(LAUNCH_NAV_PER_UNIT),
            ),
        )
        val calculated = NavRecord(
            id = random.nextUuid(),
            fundId = fundId,
            valuationDate = valuationDate,
            figures = figures,
            status = NavStatus.CALCULATED,
            calculatedBy = CALCULATOR_IDENTITY,
            calculatedAt = publishedAt,
        )
        // The previous published NAV is history the moment the next one publishes.
        book.latestPublishedNav(fundId)?.let { book.navs[book.navs.indexOf(it)] = it.supersede() }
        val published = calculated.publish(APPROVER_IDENTITY, publishedAt)
        book.navs.add(published)
        world.audit.append("pension-fund NAV ${published.id} published for $fundId at ${published.navPerUnit}")
        return fundId
    }

    private fun settlePending(world: World) {
        val book = world.pensionFundBook
        val random = world.context.random
        book.pendingOrders().toList().forEach { order ->
            val nav = book.settleableNav(order.fundId, order.placedAt) ?: return@forEach
            if (world.context.faults.shouldFailWrite()) {
                world.audit.append("pension-fund order ${order.id} settlement deferred by write fault")
                return@forEach
            }
            val key = ContractFund(order.contractId, order.fundId)
            val holding = book.holdingOf(key)
            // A redeem/switch placed against an earlier holding snapshot may exceed what the
            // register still holds (a sibling order spent the units first): defer it — the
            // domain's holding init refuses a negative register, so settling it would throw
            // instead of violating an invariant. The next subscribe re-fills the headroom.
            if (order.units != null && holding.units.compareTo(order.units) < 0) {
                world.audit.append(
                    "pension-fund order ${order.id} deferred: holding ${holding.units} below ${order.units}",
                )
                return@forEach
            }
            val settlement = ForwardPricer.settle(
                order,
                nav,
                holding,
                random.nextUuid(),
                random.nextUuid(),
            )
            book.orders[book.orders.indexOfFirst { it.id == order.id }] = settlement.order
            book.recordHolding(settlement.holding)
            book.transactions.add(settlement.transaction)
            settlement.followUp?.let { leg ->
                // The leg carries the parent's placedAt: it queues at the NEXT published NAV the
                // same way the parent did — a leg time-stamped at settlement would be priced
                // backward at the NAV its parent just settled at.
                book.orders.add(leg.copy(placedAt = order.placedAt))
            }
            world.audit.append("pension-fund order ${order.id} settled at NAV ${nav.id}")
        }
    }

    private fun maybeChargeFee(world: World, publishedFunds: List<UUID>) {
        val book = world.pensionFundBook
        val random = world.context.random
        if (!random.chance(FAIR_CHANCE) || publishedFunds.isEmpty()) return
        val publishedFund = publishedFunds[random.nextInt(publishedFunds.size)]
        val nav = book.latestPublishedNav(publishedFund) ?: return
        // One amount drawn up front: the affordability screen and the charge must price the
        // SAME number, or the screen could pass an amount the charge then refuses.
        val amount = minorAmount(random.nextLong(MIN_FEE_MINOR, MAX_FEE_MINOR))
        val candidates = book.holdingKeys().filter { it.fundId == publishedFund }
        if (candidates.isEmpty()) return
        val key = candidates[random.nextInt(candidates.size)]
        val holding = book.holdingOf(key)
        val feeUnits = Precision.unitsCancelledForFee(amount, nav.navPerUnit)
        if (holding.units < feeUnits) return
        val (tx, updated) = ForwardPricer.chargeFee(holding, amount, nav, random.nextUuid())
        book.recordHolding(updated)
        book.transactions.add(tx)
        world.audit.append("pension-fund fee ${tx.id} charged to ${key.contractId}: $amount")
    }

    /** Occasionally re-price one settled transaction at a corrected NAV and apply the unit delta. */
    private fun maybeCorrect(world: World, valuationDate: java.time.LocalDate, now: java.time.Instant) {
        val book = world.pensionFundBook
        val random = world.context.random
        if (!random.chance(FAIR_CHANCE)) return
        val correctable = book.transactions.filter { it.correctedFromNavId == null }
        if (correctable.isEmpty()) return
        val original = correctable[random.nextInt(correctable.size)]
        val oldNav = book.navs.single { it.id == original.navId }
        if (oldNav.status != NavStatus.PUBLISHED) return

        // The correction is a NEW record; the original NAV is SUPERSEDED, never edited (#12425).
        val corrected = NavRecord(
            id = random.nextUuid(),
            fundId = original.fundId,
            valuationDate = valuationDate,
            figures = NavCalculator.calculate(
                NavInput(
                    positions = listOf(
                        PricedPosition(
                            instrumentId = "INSTR-CORR",
                            quantity = BigDecimal.ONE,
                            price = minorAmount(random.nextLong(1, MAX_POSITION_PRICE_MINOR)),
                        ),
                    ),
                    cash = minorAmount(FUND_CASH_MINOR),
                    otherLiabilities = BigDecimal.ZERO.setScale(Precision.MONEY_SCALE),
                    unitsOutstanding = book.unitsOutstanding(original.fundId),
                    managementFeeRate = BigDecimal(MANAGEMENT_FEE_RATE_PERCENT).movePointLeft(2),
                    accrualDays = 1,
                    launchNavPerUnit = BigDecimal(LAUNCH_NAV_PER_UNIT),
                ),
            ),
            status = NavStatus.CALCULATED,
            calculatedBy = CALCULATOR_IDENTITY,
            calculatedAt = now,
            correctsNavId = oldNav.id,
        ).publish(APPROVER_IDENTITY, now)
        val correction = ForwardPricer.reprice(original, corrected)
        val key = ContractFund(original.contractId, original.fundId)
        // The corrected NAV demands units the register no longer holds (a redeem or fee already
        // took them): the correction cannot claw back what does not exist, and the domain's
        // holding init refuses a negative register. Production routes this to manual handling —
        // the scenario skips BOTH sides so the book stays consistent. (Computed as a plain
        // sum: UnitHolding.plus throws on a negative result before any guard could inspect it.)
        val correctedUnits = book.holdingOf(key).units.add(correction.unitsDelta)
        if (correctedUnits.signum() < 0) {
            world.audit.append("pension-fund correction of ${original.id} skipped: holding would go negative")
            return
        }
        book.navs[book.navs.indexOf(oldNav)] = oldNav.supersede()
        book.navs.add(corrected)
        book.replaceTransaction(original, correction.transaction)
        if (correction.unitsDelta.signum() != 0) {
            book.recordHolding(book.holdingOf(key).plus(correction.unitsDelta))
        }
        world.audit.append("pension-fund transaction ${original.id} repriced at corrected NAV ${corrected.id}")
    }

    /** Whole-currency amounts: minor units in, a 2-dp [BigDecimal] out (the bounds require ≤ 2dp). */
    private fun minorAmount(minorUnits: Long): BigDecimal = BigDecimal(minorUnits).movePointLeft(2)
}
