// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.`in`

import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.ProductType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class DraftDealCommand(
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val tradeDate: LocalDate?,
    val valueDate: LocalDate,
    val maturityDate: LocalDate?,
    val rationale: String?,
)

data class DealView(val deal: Deal, val journals: List<LedgerJournalRef>)

data class CounterpartyExposure(
    val counterparty: Counterparty,
    val currency: String,
    val limit: BigDecimal,
    val exposure: BigDecimal,
) {
    val headroom: BigDecimal get() = limit - exposure
}

/** Daily position per currency (outstanding principal of SETTLED deals as of a date). */
data class CurrencyPosition(
    val currency: String,
    val placed: BigDecimal,
    val borrowed: BigDecimal,
    val atCnb: BigDecimal,
) {
    val net: BigDecimal get() = placed + atCnb - borrowed
}

data class SimulatedMarketRun(val moved: Int, val failures: List<Throwable>)

@Suppress("TooManyFunctions")
interface TreasuryDealUseCase {
    /**
     * Every command takes the client's idempotency key (null only for the in-process simulated
     * market). A replayed key returns the deal as it now stands; a key reused for a different
     * command or deal is refused (400).
     */
    suspend fun draft(command: DraftDealCommand, actor: Actor, key: String? = null): Deal
    suspend fun submit(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun approve(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun reject(dealId: UUID, reason: String, actor: Actor, key: String? = null): Deal
    suspend fun cancel(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun settle(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun mature(dealId: UUID, actor: Actor, key: String? = null): Deal
    suspend fun reverse(dealId: UUID, reason: String, actor: Actor, key: String? = null): Deal
    suspend fun get(dealId: UUID): DealView
    suspend fun list(state: DealState?): List<Deal>
    suspend fun counterparties(): List<CounterpartyExposure>
    suspend fun positions(asOf: LocalDate): List<CurrencyPosition>

    /** One pass of the simulated market (ADR-0315 D9): settle and mature everything due. */
    suspend fun runSimulatedMarket(): SimulatedMarketRun
}
