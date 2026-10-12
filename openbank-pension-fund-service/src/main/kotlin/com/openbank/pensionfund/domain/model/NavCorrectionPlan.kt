// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.util.UUID

/** A calculation only. None of these values is a journal entry, holding, or payment instruction. */
data class NavCorrectionLine(
    val originalTransactionId: UUID,
    val originalOrderId: UUID?,
    val linkedSwitchOrderId: UUID?,
    val linkedSwitchTransactionId: UUID?,
    val linkedSwitchCurrency: String?,
    val contractId: UUID,
    val fundId: UUID,
    val currency: String,
    val type: UnitTransactionType,
    val originalUnits: BigDecimal,
    val proposedUnits: BigDecimal,
    val unitsDelta: BigDecimal,
    val originalAmount: BigDecimal,
    val proposedAmount: BigDecimal,
    val cashDelta: BigDecimal,
    /** Signed difference between the unrounded value and the value implied by proposed units/amount. */
    val roundingResidual: BigDecimal,
)

enum class NavCorrectionBlocker {
    ROUNDING_RECONCILIATION_REQUIRED,
    INSUFFICIENT_HELD_UNITS,
    SWITCH_COUNTERPART_MISSING,
    SWITCH_COUNTERPART_REPRICE_REQUIRED,
    SWITCH_COUNTERPART_CURRENCY_UNVERIFIED,
    CROSS_CURRENCY_SWITCH_POLICY_REQUIRED,
}

data class NavCorrectionIssue(
    val blocker: NavCorrectionBlocker,
    val originalTransactionId: UUID?,
    val contractId: UUID,
    val fundId: UUID,
)

data class NavCorrectionPlan(
    val originalNavId: UUID,
    val replacementNavId: UUID,
    val fundCurrency: String,
    val lines: List<NavCorrectionLine>,
    val unresolved: List<NavCorrectionIssue>,
)

/**
 * Computes candidate differences without changing the original transaction or choosing a cash,
 * unit, switch, or recovery policy. Correction publication remains disabled in NavService.
 */
object NavCorrectionPlanner {
    private data class LinkContext(
        val orders: List<UnitOrder>,
        val ordersById: Map<UUID, UnitOrder>,
        val transactionsByOrder: Map<UUID, UnitTransaction>,
        val relatedFundsById: Map<UUID, Fund>,
    )

    fun calculate(
        original: NavRecord,
        replacement: NavRecord,
        fund: Fund,
        originalTransactions: List<UnitTransaction>,
        relatedOrders: List<UnitOrder>,
        relatedTransactions: List<UnitTransaction>,
        relatedFunds: List<Fund>,
        holdings: List<UnitHolding>,
    ): NavCorrectionPlan {
        validateInput(original, replacement, fund, originalTransactions)
        validateRelatedRecords(originalTransactions, relatedOrders, relatedTransactions, relatedFunds, holdings)
        val context = LinkContext(
            relatedOrders,
            relatedOrders.associateBy { it.id },
            relatedTransactions.associateBy { checkNotNull(it.orderId) },
            relatedFunds.associateBy { it.id },
        )
        val calculations = originalTransactions.map { tx ->
            calculateLine(tx, replacement.navPerUnit, fund.currency, context)
        }
        val lines = calculations.map { it.first }
        val issues = calculations.flatMap { it.second } + insufficientHoldings(lines, holdings)
        return NavCorrectionPlan(original.id, replacement.id, fund.currency, lines, issues)
    }

    private fun validateInput(
        original: NavRecord,
        replacement: NavRecord,
        fund: Fund,
        originalTransactions: List<UnitTransaction>,
    ) {
        require(fund.id == original.fundId) { "source fund must match original NAV" }
        require(original.status == NavStatus.PUBLISHED) { "original NAV must be published" }
        require(replacement.status == NavStatus.CALCULATED && replacement.correctsNavId == original.id) {
            "replacement must be a calculated correction of the original NAV"
        }
        require(replacement.fundId == original.fundId && replacement.valuationDate == original.valuationDate) {
            "replacement must value the original fund and date"
        }
        require(originalTransactions.map { it.id }.distinct().size == originalTransactions.size) {
            "duplicate original transaction"
        }
        require(originalTransactions.all { it.navId == original.id && it.fundId == original.fundId }) {
            "all original transactions must belong to the original NAV"
        }
        require(originalTransactions.all { it.navPerUnit.compareTo(original.navPerUnit) == 0 }) {
            "original transaction price must match original NAV"
        }
    }

    private fun calculateLine(
        tx: UnitTransaction,
        price: BigDecimal,
        fundCurrency: String,
        context: LinkContext,
    ): Pair<NavCorrectionLine, List<NavCorrectionIssue>> {
        val proposedUnits = proposedUnits(tx, price)
        val proposedAmount = proposedAmount(tx, price)
        val unitsDelta = unitsDelta(tx, proposedUnits)
        val cashDelta = proposedAmount - tx.amount
        val residual = roundingResidual(tx, price, proposedUnits, proposedAmount)
        val counterpart = switchCounterpart(tx, context)
        val counterpartCurrency = counterpart?.fundId?.let { context.relatedFundsById[it]?.currency }
        val issues = lineIssues(tx, residual, counterpart, counterpartCurrency, fundCurrency)
        val line = NavCorrectionLine(
            originalTransactionId = tx.id,
            originalOrderId = tx.orderId,
            linkedSwitchOrderId = counterpart?.id,
            linkedSwitchTransactionId = counterpart?.id?.let { context.transactionsByOrder[it]?.id },
            linkedSwitchCurrency = counterpartCurrency,
            contractId = tx.contractId,
            fundId = tx.fundId,
            currency = fundCurrency,
            type = tx.type,
            originalUnits = tx.units,
            proposedUnits = proposedUnits,
            unitsDelta = unitsDelta,
            originalAmount = tx.amount,
            proposedAmount = proposedAmount,
            cashDelta = cashDelta,
            roundingResidual = residual,
        )
        return line to issues
    }

    private fun lineIssues(
        tx: UnitTransaction,
        residual: BigDecimal,
        counterpart: UnitOrder?,
        counterpartCurrency: String?,
        fundCurrency: String,
    ): List<NavCorrectionIssue> {
        val issues = mutableListOf<NavCorrectionIssue>()
        if (residual.signum() != 0) {
            issues +=
                NavCorrectionIssue(
                    NavCorrectionBlocker.ROUNDING_RECONCILIATION_REQUIRED,
                    tx.id,
                    tx.contractId,
                    tx.fundId,
                )
        }
        if (tx.type == UnitTransactionType.SWITCH_IN || tx.type == UnitTransactionType.SWITCH_OUT) {
            val blocker = if (counterpart == null) {
                NavCorrectionBlocker.SWITCH_COUNTERPART_MISSING
            } else {
                NavCorrectionBlocker.SWITCH_COUNTERPART_REPRICE_REQUIRED
            }
            issues += NavCorrectionIssue(blocker, tx.id, tx.contractId, tx.fundId)
            if (counterpartCurrency == null) {
                issues += NavCorrectionIssue(
                    NavCorrectionBlocker.SWITCH_COUNTERPART_CURRENCY_UNVERIFIED,
                    tx.id,
                    tx.contractId,
                    tx.fundId,
                )
            } else if (counterpartCurrency != fundCurrency) {
                issues +=
                    NavCorrectionIssue(
                        NavCorrectionBlocker.CROSS_CURRENCY_SWITCH_POLICY_REQUIRED,
                        tx.id,
                        tx.contractId,
                        tx.fundId,
                    )
            }
        }
        return issues
    }

    private fun proposedUnits(tx: UnitTransaction, price: BigDecimal): BigDecimal = when (tx.type) {
        UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN -> Precision.unitsIssued(tx.amount, price)
        UnitTransactionType.FEE -> Precision.unitsCancelledForFee(tx.amount, price)
        else -> tx.units
    }

    private fun proposedAmount(tx: UnitTransaction, price: BigDecimal): BigDecimal = when (tx.type) {
        UnitTransactionType.REDEEM, UnitTransactionType.SWITCH_OUT -> Precision.proceeds(tx.units, price)
        else -> tx.amount
    }

    private fun unitsDelta(tx: UnitTransaction, proposed: BigDecimal): BigDecimal = when (tx.type) {
        UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN -> proposed - tx.units
        UnitTransactionType.FEE -> tx.units - proposed
        else -> BigDecimal.ZERO.setScale(Precision.UNIT_SCALE)
    }

    private fun roundingResidual(
        tx: UnitTransaction,
        price: BigDecimal,
        proposedUnits: BigDecimal,
        proposedAmount: BigDecimal,
    ): BigDecimal = when (tx.type) {
        UnitTransactionType.SUBSCRIBE, UnitTransactionType.SWITCH_IN, UnitTransactionType.FEE ->
            tx.amount - proposedUnits.multiply(price)
        UnitTransactionType.REDEEM, UnitTransactionType.SWITCH_OUT ->
            tx.units.multiply(price) - proposedAmount
    }

    private fun switchCounterpart(tx: UnitTransaction, context: LinkContext): UnitOrder? {
        val order = context.ordersById[tx.orderId]
        return when (tx.type) {
            UnitTransactionType.SWITCH_OUT -> context.orders.singleOrNull {
                it.parentOrderId == tx.orderId &&
                    it.type == OrderType.SWITCH_IN &&
                    it.contractId == tx.contractId &&
                    it.fundId == order?.targetFundId
            }
            UnitTransactionType.SWITCH_IN -> context.orders.singleOrNull {
                it.id == order?.parentOrderId &&
                    it.type == OrderType.SWITCH_OUT &&
                    it.contractId == tx.contractId &&
                    it.targetFundId == tx.fundId
            }
            else -> null
        }
    }

    private fun insufficientHoldings(
        lines: List<NavCorrectionLine>,
        holdings: List<UnitHolding>,
    ): List<NavCorrectionIssue> {
        val heldByContract = holdings.associateBy { it.contractId to it.fundId }
        return lines.groupBy { it.contractId to it.fundId }.mapNotNull { (key, group) ->
            val delta = group.fold(BigDecimal.ZERO) { total, line -> total + line.unitsDelta }
            val held = heldByContract[key]?.units ?: BigDecimal.ZERO
            if (held + delta < BigDecimal.ZERO) {
                NavCorrectionIssue(NavCorrectionBlocker.INSUFFICIENT_HELD_UNITS, null, key.first, key.second)
            } else {
                null
            }
        }
    }
}

private fun validateRelatedRecords(
    originalTransactions: List<UnitTransaction>,
    orders: List<UnitOrder>,
    transactions: List<UnitTransaction>,
    funds: List<Fund>,
    holdings: List<UnitHolding>,
) {
    require(orders.map { it.id }.distinct().size == orders.size) { "duplicate related order" }
    require(transactions.map { it.id }.distinct().size == transactions.size) { "duplicate related transaction" }
    require(
        transactions.map { it.orderId }.distinct().size == transactions.size &&
            transactions.none { it.orderId == null },
    ) { "duplicate or missing related transaction order" }
    require(transactions.none { tx -> originalTransactions.any { it.id == tx.id } }) {
        "related transaction must differ from original transaction"
    }
    require(funds.map { it.id }.distinct().size == funds.size) { "duplicate related fund" }
    require(holdings.map { it.contractId to it.fundId }.distinct().size == holdings.size) { "duplicate holding" }
    val ordersById = orders.associateBy { it.id }
    require(
        (originalTransactions + transactions).all { tx ->
            tx.orderId == null ||
                ordersById[tx.orderId]?.let { order ->
                    order.contractId == tx.contractId &&
                        order.fundId == tx.fundId &&
                        order.type.name == tx.type.name
                } == true
        },
    ) { "transaction order identity or type mismatch" }
}
