// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.runner

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.settlement.domain.model.Settlement
import com.openbank.simulation.adapters.AuditLog
import com.openbank.simulation.adapters.BalanceProjection
import com.openbank.simulation.adapters.SimEventBus
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.BalanceStore
import com.openbank.simulation.model.BillingFeeLedger
import com.openbank.simulation.model.DelegationSpendBook
import com.openbank.simulation.model.InterestAccrualBook
import com.openbank.simulation.model.LedgerState
import com.openbank.simulation.model.SddCollectionBook
import com.openbank.simulation.model.SimPaymentSaga
import com.openbank.simulation.model.StandingOrderBook
import com.openbank.simulation.model.StatementCloseBook
import com.openbank.simulation.model.SwiftBook
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.swift.domain.model.SwiftMessage
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

    // Issue #14366 (Test Intelligence simulation gap, money path): the REAL `DomesticPayment`
    // (domestic-payment) domain aggregate driven by DomesticClearingScenario, kept here so the
    // Layer-3 invariants can assert every domestic payment reaches a terminal state. Same
    // convention as sepaPayments/settlements above.
    val domesticPayments = mutableListOf<DomesticPayment>()

    // ADR-0143 phase 2d/2e: the billing fee-conservation invariant's state (Σ fees assessed == Σ
    // fee journals posted per cycle/account/fee/currency). Populated by FeeBillingScenario every
    // step (previously unpopulated by any scenario, which made the invariant hold vacuously).
    val billingFees = BillingFeeLedger()

    // ADR-0033 / issue #667: the interest-conservation invariant's state — daily accruals, the
    // gross/net/tax capitalization split (real WithholdingTaxPolicy) and the journal legs that
    // actually landed. Populated by InterestAccrualScenario; empty (trivially satisfied) otherwise.
    val interest = InterestAccrualBook()

    // ADR-0035/0078 / issue #667: the statement-close integrity invariant's state — every close
    // attempt's ReconciliationPolicy decision cross-checked against whether a period was actually
    // persisted. Populated by StatementCloseScenario; empty (trivially satisfied) otherwise.
    val statementCloses = StatementCloseBook()

    // The standing-order execution state: the REAL `StandingOrder` aggregates driven by
    // StandingOrderScenario plus the both-sides execution book the completeness invariant
    // reconciles (status-machine counts vs money actually moved). Framework-free domain
    // (ADR-0002), same convention as sepaPayments/settlements above.
    val standingOrders = mutableListOf<StandingOrder>()
    val standingOrdersBook = StandingOrderBook()

    // The SDD collection state: the REAL `SddMandate` aggregates driven by SddCollectionScenario
    // plus the both-sides collection book (posted amount vs the mandate's own collection stamp).
    // Framework-free domain (ADR-0002), same convention as sepaPayments/settlements above.
    val sddMandates = mutableListOf<SddMandate>()
    val sddCollections = SddCollectionBook()

    // ADR-0249 D3: the delegated-spend invariants' state — the REAL `DelegationGrant` aggregates
    // and `SpendReservation`s driven by DelegatedSpendScenario, plus the per-grant book of
    // ceiling decisions and confirmed/posted amounts that the conservation and ceiling
    // invariants replay. Empty (trivially satisfied) otherwise.
    val delegationGrants = mutableListOf<DelegationGrant>()
    val spendReservations = mutableListOf<SpendReservation>()
    val delegationSpends = DelegationSpendBook()

    // The SWIFT rail's invariant state: the REAL `SwiftMessage` objects driven by
    // SwiftScenario (its validate() admission gate and PENDING→…→COMPLETED/REJECTED/FAILED
    // lifecycle), plus the per-message posted amounts the conservation invariant replays.
    // Empty (trivially satisfied) otherwise.
    val swiftMessages = mutableListOf<SwiftMessage>()
    val swiftBook = SwiftBook()

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
