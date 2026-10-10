// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.ContractVisibility
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.PendingFundOrder
import com.openbank.pension.application.port.out.PensionContractRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Why a contract's total is, or is not, stated. A total is only ever the sum of values the unit
 * register itself priced at a PUBLISHED NAV; the view never fills a gap with a launch price or a
 * stale number.
 */
enum class ValuationStatus {
    /** Every holding is priced at a published NAV in the contract currency: [ContractValuationView.totalValue] is set. */
    VALUED,

    /** The contract holds no units (pending orders may still exist): the total is zero, not unknown. */
    NO_HOLDINGS,

    /** At least one fund has no published NAV yet: the total is NOT stated. */
    NAV_NOT_PUBLISHED,

    /** A holding is in another currency than the contract: the total is NOT stated (no FX guess). */
    CURRENCY_MISMATCH,
}

data class ContractValuationView(
    val contractId: UUID,
    val currency: String,
    val status: ValuationStatus,
    /** Null unless [status] is VALUED or NO_HOLDINGS. */
    val totalValue: BigDecimal?,
    /** The latest NAV date among the priced holdings; null when nothing is priced. */
    val asOf: LocalDate?,
    val holdings: List<FundHolding>,
    val pendingOrders: List<PendingFundOrder>,
)

data class TransactionPage(val items: List<FundUnitTransaction>, val page: Int, val size: Int, val total: Int)

/**
 * The participant's valuation and unit-transaction history (ADR-0334): pension-service owns the
 * contract and its ownership rule, pension-fund-service owns the units. Every read resolves the
 * contract and applies [ContractVisibility] BEFORE the register is asked anything, so a foreign
 * contract is a 404 and never reaches pension-fund-service.
 */
class ContractValuationService(
    private val contracts: PensionContractRepository,
    private val fund: FundAdministrationPort,
) {

    suspend fun valuation(caller: Caller, contractId: UUID): ContractValuationView {
        val contract = visible(caller, contractId)
        val currency = contract.schedule.currency
        val register = fund.holdings(contractId)
        val held = register.holdings.filter { it.units.signum() > 0 }
        val status = when {
            held.isEmpty() -> ValuationStatus.NO_HOLDINGS
            held.any { it.currency != currency } -> ValuationStatus.CURRENCY_MISMATCH
            held.any { it.value == null || it.navPerUnit == null } -> ValuationStatus.NAV_NOT_PUBLISHED
            else -> ValuationStatus.VALUED
        }
        val total = when (status) {
            ValuationStatus.VALUED -> held.fold(BigDecimal.ZERO) { acc, h -> acc + h.value!! }
            ValuationStatus.NO_HOLDINGS -> BigDecimal.ZERO
            else -> null
        }?.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
        return ContractValuationView(
            contractId = contractId,
            currency = currency,
            status = status,
            totalValue = total,
            asOf = held.mapNotNull { it.navDate }.maxOrNull(),
            holdings = held,
            pendingOrders = register.pendingOrders,
        )
    }

    suspend fun transactions(caller: Caller, contractId: UUID, page: Int, size: Int): TransactionPage {
        require(page >= 0) { "query parameter 'page' must be >= 0" }
        require(size in 1..MAX_PAGE_SIZE) { "query parameter 'size' must be between 1 and $MAX_PAGE_SIZE" }
        visible(caller, contractId)
        val all = fund.transactions(contractId).sortedByDescending { it.pricedAt }
        val from = page.toLong() * size
        val items = if (from >=
            all.size
        ) {
            emptyList()
        } else {
            all.subList(from.toInt(), minOf(all.size, from.toInt() + size))
        }
        return TransactionPage(items, page, size, all.size)
    }

    private suspend fun visible(caller: Caller, contractId: UUID) = ContractVisibility.requireVisible(
        caller,
        contracts.findById(contractId) ?: throw ContractNotFoundException(contractId),
    )

    companion object {
        const val MAX_PAGE_SIZE = 200
        private const val MONEY_SCALE = 2
    }
}
