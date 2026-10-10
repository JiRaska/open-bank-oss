// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class OrderType { SUBSCRIBE, REDEEM, SWITCH_OUT, SWITCH_IN }

enum class OrderStatus { PENDING, SETTLED }

enum class UnitTransactionType { SUBSCRIBE, REDEEM, SWITCH_OUT, SWITCH_IN, FEE }

/**
 * An order against the unit register. It carries no price: it queues until the fund's NEXT
 * published NAV (forward pricing), so nobody can trade at a price already known when they ordered.
 *
 * Amount-based: SUBSCRIBE and SWITCH_IN (money in, units computed). Unit-based: REDEEM and
 * SWITCH_OUT (units out, proceeds computed). A switch is placed as a SWITCH_OUT on the source fund
 * naming [targetFundId]; its settlement spawns the SWITCH_IN order for the proceeds, which then
 * waits for the TARGET fund's next NAV — both legs are forward-priced.
 */
data class UnitOrder(
    val id: UUID,
    val contractId: UUID,
    val fundId: UUID,
    val type: OrderType,
    val amount: BigDecimal?,
    val units: BigDecimal?,
    val targetFundId: UUID?,
    val parentOrderId: UUID?,
    val status: OrderStatus,
    val placedAt: Instant,
    val settledAt: Instant? = null,
    val navId: UUID? = null,
    val idempotencyKey: String? = null,
) {
    init {
        require(idempotencyKey == null || idempotencyKey.length in 1..MAX_KEY_LENGTH) {
            "idempotency key must be 1..128 characters"
        }
        when (type) {
            OrderType.SUBSCRIBE, OrderType.SWITCH_IN -> {
                require(amount != null && amount.signum() > 0) { "$type needs a positive amount" }
                require(units == null) { "$type is amount-based; units must be absent" }
            }
            OrderType.REDEEM, OrderType.SWITCH_OUT -> {
                require(units != null && units.signum() > 0) { "$type needs a positive number of units" }
                require(amount == null) { "$type is unit-based; amount must be absent" }
            }
        }
        require((type == OrderType.SWITCH_OUT) == (targetFundId != null)) { "only a switch names a target fund" }
        require(targetFundId != fundId) { "a switch must target a different fund" }
    }

    val isOutgoing: Boolean get() = type == OrderType.REDEEM || type == OrderType.SWITCH_OUT

    /** Same instruction as [other], ignoring identity and lifecycle — what a replay must match. */
    fun sameInstructionAs(other: UnitOrder): Boolean = contractId == other.contractId &&
        fundId == other.fundId &&
        type == other.type &&
        targetFundId == other.targetFundId &&
        sameNumber(amount, other.amount) &&
        sameNumber(units, other.units)

    private fun sameNumber(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

/** [version] is the persistence lock token read with the holding; null for one never stored. */
data class UnitHolding(val contractId: UUID, val fundId: UUID, val units: BigDecimal, val version: Long? = null) {
    init {
        require(units.signum() >= 0) { "a holding cannot go negative" }
    }

    fun plus(delta: BigDecimal) = copy(units = Precision.units(units + delta))
}

data class UnitTransaction(
    val id: UUID,
    val orderId: UUID?,
    val contractId: UUID,
    val fundId: UUID,
    val type: UnitTransactionType,
    val units: BigDecimal,
    val amount: BigDecimal,
    val navId: UUID,
    val navPerUnit: BigDecimal,
    val pricedAt: Instant,
    val correctedFromNavId: UUID? = null,
)

data class Settlement(
    val order: UnitOrder,
    val transaction: UnitTransaction,
    val holding: UnitHolding,
    /** The SWITCH_IN leg a settled SWITCH_OUT spawns, still unpriced. */
    val followUp: UnitOrder?,
)

/** The outcome of re-pricing one transaction at a corrected NAV: what the participant is owed. */
data class TransactionCorrection(
    val transaction: UnitTransaction,
    val unitsDelta: BigDecimal,
    val amountDelta: BigDecimal,
)

object ForwardPricer {
    /**
     * Price [order] at [nav]. Refuses a NAV that was already published when the order was placed,
     * and a valuation date before the order's trade date — either would be backward pricing.
     */
    fun settle(
        order: UnitOrder,
        nav: NavRecord,
        holding: UnitHolding,
        transactionId: UUID,
        followUpId: UUID,
    ): Settlement {
        val publishedAt = requireForwardPrice(order, nav, holding)

        val (units, amount, txType) = when (order.type) {
            OrderType.SUBSCRIBE -> Triple(
                Precision.unitsIssued(order.amount!!, nav.navPerUnit),
                Precision.money(order.amount),
                UnitTransactionType.SUBSCRIBE,
            )
            OrderType.SWITCH_IN -> Triple(
                Precision.unitsIssued(order.amount!!, nav.navPerUnit),
                Precision.money(order.amount),
                UnitTransactionType.SWITCH_IN,
            )
            OrderType.REDEEM -> Triple(
                order.units!!,
                Precision.proceeds(order.units, nav.navPerUnit),
                UnitTransactionType.REDEEM,
            )
            OrderType.SWITCH_OUT -> Triple(
                order.units!!,
                Precision.proceeds(order.units, nav.navPerUnit),
                UnitTransactionType.SWITCH_OUT,
            )
        }
        check(!order.isOutgoing || holding.units >= units) {
            "contract ${order.contractId} holds ${holding.units} units of fund ${order.fundId}, cannot sell $units"
        }
        val newHolding = holding.plus(if (order.isOutgoing) units.negate() else units)
        val transaction = UnitTransaction(
            id = transactionId,
            orderId = order.id,
            contractId = order.contractId,
            fundId = order.fundId,
            type = txType,
            units = Precision.units(units),
            amount = amount,
            navId = nav.id,
            navPerUnit = nav.navPerUnit,
            pricedAt = publishedAt,
        )
        val followUp = if (order.type ==
            OrderType.SWITCH_OUT
        ) {
            switchInLeg(order, amount, publishedAt, followUpId)
        } else {
            null
        }
        return Settlement(
            order = order.copy(status = OrderStatus.SETTLED, settledAt = publishedAt, navId = nav.id),
            transaction = transaction,
            holding = newHolding,
            followUp = followUp,
        )
    }

    private fun requireForwardPrice(order: UnitOrder, nav: NavRecord, holding: UnitHolding): Instant {
        check(order.status == OrderStatus.PENDING) { "order ${order.id} is already settled" }
        check(nav.status == NavStatus.PUBLISHED) { "order ${order.id} can only be priced at a published NAV" }
        check(nav.fundId == order.fundId) { "NAV ${nav.id} is for another fund" }
        val publishedAt = checkNotNull(nav.publishedAt)
        check(publishedAt.isAfter(order.placedAt)) { "NAV ${nav.id} was known when order ${order.id} was placed" }
        check(!nav.valuationDate.isBefore(order.placedAt.atZone(ZoneOffset.UTC).toLocalDate())) {
            "NAV ${nav.id} values a day before order ${order.id} was placed"
        }
        check(holding.contractId == order.contractId && holding.fundId == order.fundId) { "holding mismatch" }
        return publishedAt
    }

    private fun switchInLeg(switchOut: UnitOrder, proceeds: BigDecimal, at: Instant, id: UUID) = UnitOrder(
        id = id,
        contractId = switchOut.contractId,
        fundId = checkNotNull(switchOut.targetFundId),
        type = OrderType.SWITCH_IN,
        amount = proceeds,
        units = null,
        targetFundId = null,
        parentOrderId = switchOut.id,
        status = OrderStatus.PENDING,
        placedAt = at,
    )

    /** Charge a fee by cancelling units at a published NAV; the cancelled units are rounded UP. */
    fun chargeFee(
        holding: UnitHolding,
        amount: BigDecimal,
        nav: NavRecord,
        transactionId: UUID,
    ): Pair<UnitTransaction, UnitHolding> {
        require(amount.signum() > 0) { "a fee must be positive" }
        check(nav.status == NavStatus.PUBLISHED && nav.fundId == holding.fundId) {
            "fee needs this fund's published NAV"
        }
        val units = Precision.unitsCancelledForFee(Precision.money(amount), nav.navPerUnit)
        check(holding.units >= units) { "holding cannot cover the fee" }
        val tx = UnitTransaction(
            id = transactionId,
            orderId = null,
            contractId = holding.contractId,
            fundId = holding.fundId,
            type = UnitTransactionType.FEE,
            units = units,
            amount = Precision.money(amount),
            navId = nav.id,
            navPerUnit = nav.navPerUnit,
            pricedAt = checkNotNull(nav.publishedAt),
        )
        return tx to holding.plus(units.negate())
    }

    /**
     * Re-price a transaction at a corrected NAV. Money-in transactions keep their amount and get
     * re-computed units; units-out transactions keep their units and get re-computed proceeds; a fee
     * keeps its amount and gets re-computed cancelled units. The deltas are what the participant is
     * owed (positive) or owes (negative), so the correction is compensation, not silent restatement.
     */
    fun reprice(tx: UnitTransaction, corrected: NavRecord): TransactionCorrection {
        check(corrected.status == NavStatus.PUBLISHED && corrected.fundId == tx.fundId) {
            "correction must be this fund's published NAV"
        }
        val price = corrected.navPerUnit
        val updated = when (tx.type) {
            UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN -> tx.copy(
                units = Precision.unitsIssued(tx.amount, price),
            )
            UnitTransactionType.REDEEM, UnitTransactionType.SWITCH_OUT -> tx.copy(
                amount = Precision.proceeds(tx.units, price),
            )
            UnitTransactionType.FEE -> tx.copy(units = Precision.unitsCancelledForFee(tx.amount, price))
        }.copy(navId = corrected.id, navPerUnit = price, correctedFromNavId = tx.navId)
        val unitsDelta = when (tx.type) {
            UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN -> updated.units - tx.units
            UnitTransactionType.FEE -> tx.units - updated.units
            else -> BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)
        }
        val amountDelta = when (tx.type) {
            UnitTransactionType.REDEEM, UnitTransactionType.SWITCH_OUT -> updated.amount - tx.amount
            else -> BigDecimal.ZERO.setScale(Precision.MONEY_SCALE)
        }
        return TransactionCorrection(updated, unitsDelta, amountDelta)
    }
}
