// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.runner

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.settlement.domain.model.Settlement
import com.openbank.simulation.adapters.AuditLog
import com.openbank.simulation.adapters.BalanceProjection
import com.openbank.simulation.adapters.SimEventBus
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.BalanceStore
import com.openbank.simulation.model.LedgerState
import com.openbank.simulation.model.SimPaymentSaga
import java.math.BigDecimal
import java.util.UUID

/**
 * Tunable parameters for a simulation run. [dedupEnabled] is the fault-INJECTION toggle: flip
 * it off to introduce a realistic money-path defect and prove the harness catches it (the DST
 * equivalent of a mutation).
 */
data class SimulationConfig(
    val customerAccountCount: Int = 4,
    val currency: String = "CZK",
    val openingBooked: BigDecimal = BigDecimal("10000.00"),
    val overdraftLimit: BigDecimal = BigDecimal.ZERO,
    /**
     * Production guards the ledger→balance projection against duplicate delivery. Turning this
     * off injects a realistic idempotency-gap defect that the DST invariants then catch.
     */
    val dedupEnabled: Boolean = true,
)

/**
 * The mutable state of one simulation run: the in-memory ledger, balances, audit chain, the
 * payment sagas executed so far, and the projection bus wiring. Built fresh per seed so runs
 * are independent.
 */
class World(val context: SimulationContext, val config: SimulationConfig) {
    val ledger = LedgerState()
    val balances = BalanceStore()
    val audit = AuditLog()
    val sagas = mutableListOf<SimPaymentSaga>()

    // Issue #267 (ADR-0100 full-service adoption): the REAL `SepaPayment` (sepa-payment) and
    // `Settlement` (settlement-service) domain aggregates driven by SepaSettlementScenario, kept
    // here so the Layer-3 invariants can assert every one reaches a terminal state.
    val sepaPayments = mutableListOf<SepaPayment>()
    val settlements = mutableListOf<Settlement>()

    val customerAccounts: List<UUID>
    val currency: String = config.currency
    private val openingBooked = mutableMapOf<AccountCurrency, BigDecimal>()

    val bus: SimEventBus

    init {
        customerAccounts = (1..config.customerAccountCount).map { context.random.nextUuid() }
        customerAccounts.forEach { id ->
            val key = AccountCurrency(id, currency)
            balances.open(key, config.openingBooked, config.overdraftLimit)
            openingBooked[key] = config.openingBooked
        }
        val projection = BalanceProjection(balances, dedupEnabled = config.dedupEnabled)
        bus = SimEventBus(context.scheduler, context.faults, projection)
    }

    /** The seeded opening booked balance for an account (0 for non-customer GL accounts). */
    fun openingBookedOf(key: AccountCurrency): BigDecimal = openingBooked[key] ?: BigDecimal.ZERO

    fun isCustomerAccount(key: AccountCurrency): Boolean = openingBooked.containsKey(key)
}
