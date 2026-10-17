// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.runner

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.settlement.domain.model.Settlement
import com.openbank.simulation.adapters.AuditLog
import com.openbank.simulation.adapters.BalanceProjection
import com.openbank.simulation.adapters.SimEventBus
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.BalanceStore
import com.openbank.simulation.model.BillingFeeLedger
import com.openbank.simulation.model.ClearingCycleBook
import com.openbank.simulation.model.InterestAccrualBook
import com.openbank.simulation.model.LedgerState
import com.openbank.simulation.model.SddCollectionBook
import com.openbank.simulation.model.SimPaymentSaga
import com.openbank.simulation.model.StatementCloseBook
import com.openbank.simulation.model.SwiftMessageBook
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

    // Money-path SCT Inst adoption: the REAL `SctInstPayment` (sepa-instant) aggregate driven by
    // SctInstPaymentScenario — same terminal-state completeness invariant as the SEPA pair above.
    val sctInstPayments = mutableListOf<SctInstPayment>()

    // Money-path clearing adoption: the REAL `ClearingItem`/`ClearingBatch`/`ClearingCycleResult`
    // aggregates (clearing-service) driven by ClearingCycleScenario, with the per-rail cycle book
    // the completeness/conservation invariants assert over — same terminal-state role as the
    // SEPA/settlement/sct-inst lists above.
    val clearing = ClearingCycleBook()

    // Money-path SEPA Direct Debit adoption: the REAL `SddMandate` aggregates (sdd-service) driven
    // through the real MandateLifecycle state machine by SddCollectionScenario, plus the
    // per-collection record the money-side invariants assert over (production has no collection
    // aggregate — the debit goes straight through the transaction path).
    val sdd = SddCollectionBook()

    // Money-path SWIFT adoption: the REAL `SwiftMessage` aggregates (swift-service) driven through
    // the real validate() admission and the production transition mapping (scheme verdict →
    // settlement → operator ack/reject) by SwiftMessageScenario, plus the per-message settlement
    // posting the money-side invariants assert over (production persists no settlement record —
    // the booking is a transaction-service journal keyed by the message, ADR-0108).
    val swift = SwiftMessageBook()

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
