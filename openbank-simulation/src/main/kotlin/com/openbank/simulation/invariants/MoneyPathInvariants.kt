// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * The ADR-0100 Layer-3 invariant set for the money path. Each is a pure function of the
 * [World] state and is checked after every step; the first to break aborts the run with a
 * reproducible seed.
 */
object MoneyPathInvariants {

    /** The settlement date every scenario and this invariant read the virtual clock as (Europe/Prague). */
    private val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    /** `Σ debit == Σ credit` across the whole ledger ⇒ net movement per currency is zero. */
    val conservationOfMoney = object : Invariant {
        override val name = "ledger-conservation-of-money"
        override fun check(world: World): Violation? {
            world.ledger.lineNetByCurrency().forEach { (currency, net) ->
                if (net.signum() != 0) {
                    return Violation(name, "currency $currency nets to $net, expected 0")
                }
            }
            return null
        }
    }

    /** No balance's available amount may fall below its overdraft floor. */
    val noNegativeBalance = object : Invariant {
        override val name = "no-negative-balance"
        override fun check(world: World): Violation? {
            world.balances.all().forEach { balance ->
                val floor = balance.arrangedOverdraftLimit.negate()
                if (balance.available() < floor) {
                    val key = AccountCurrency(balance.accountId, balance.currency)
                    return Violation(
                        name,
                        "account $key available=${balance.available()} < floor=$floor",
                    )
                }
            }
            return null
        }
    }

    /**
     * Every customer balance's projected booked movement equals the ledger's net credit-positive
     * delta for that account. This is the idempotency/projection invariant: duplicate or
     * re-delivered events must not move it (a correct, dedup-guarded consumer keeps it exact).
     */
    val projectionConsistency = object : Invariant {
        override val name = "ledger-balance-projection-consistency"
        override fun check(world: World): Violation? {
            val netDeltas = world.ledger.netDeltas()
            world.balances.all().forEach { balance ->
                val key = AccountCurrency(balance.accountId, balance.currency)
                val expected = world.openingBookedOf(key) + (netDeltas[key] ?: BigDecimal.ZERO)
                if (balance.bookedAmount.compareTo(expected) != 0) {
                    return Violation(
                        name,
                        "account $key booked=${balance.bookedAmount} but ledger projects $expected",
                    )
                }
            }
            return null
        }
    }

    /** No saga may be left in a non-terminal state once a step has fully settled. */
    val compensationCompleteness = object : Invariant {
        override val name = "compensation-completeness"
        override fun check(world: World): Violation? {
            world.sagas.forEach { saga ->
                if (!saga.isTerminal) {
                    return Violation(name, "saga ${saga.id} stuck in ${saga.state}")
                }
            }
            return null
        }
    }

    /** The audit chain verifies and carries at least one record per executed saga. */
    val auditCompleteness = object : Invariant {
        override val name = "audit-completeness"
        override fun check(world: World): Violation? {
            if (!world.audit.verifyChain()) {
                return Violation(name, "audit hash chain failed to verify")
            }
            if (world.audit.size() < world.sagas.size) {
                return Violation(
                    name,
                    "audit has ${world.audit.size()} records for ${world.sagas.size} sagas",
                )
            }
            return null
        }
    }

    /**
     * Issue #267 (ADR-0100 full-service adoption): every `SepaPayment` driven by
     * [com.openbank.simulation.scenario.SepaSettlementScenario] must reach a terminal status
     * (COMPLETED / REJECTED / RETURNED / CANCELLED) by the time a step has fully settled — the
     * SEPA analogue of [compensationCompleteness].
     */
    val sepaPaymentCompleteness = object : Invariant {
        private val terminal = setOf(
            SepaPaymentStatus.COMPLETED,
            SepaPaymentStatus.REJECTED,
            SepaPaymentStatus.RETURNED,
            SepaPaymentStatus.CANCELLED,
        )

        override val name = "sepa-payment-completeness"
        override fun check(world: World): Violation? {
            world.sepaPayments.forEach { payment ->
                if (payment.status !in terminal) {
                    return Violation(name, "sepa payment ${payment.id} stuck in ${payment.status}")
                }
            }
            return null
        }
    }

    /**
     * Issue #267: every `Settlement` driven alongside a SEPA payment must reach a terminal
     * status (BOOKED / REJECTED / REVERSED / CREDITED_REVERSED / LEDGER_REVERSED) — no
     * settlement may be left mid-flight (DEBITED/CREDITED without a following terminal state).
     */
    val settlementCompleteness = object : Invariant {
        private val terminal = setOf(
            SettlementStatus.BOOKED,
            SettlementStatus.REJECTED,
            SettlementStatus.REVERSED,
            SettlementStatus.CREDITED_REVERSED,
            SettlementStatus.LEDGER_REVERSED,
        )

        override val name = "settlement-completeness"
        override fun check(world: World): Violation? {
            world.settlements.forEach { settlement ->
                if (settlement.status !in terminal) {
                    return Violation(name, "settlement ${settlement.id} stuck in ${settlement.status}")
                }
            }
            return null
        }
    }

    /**
     * ADR-0143 phase 2d: *Σ fees assessed == Σ fee journals posted* per cycle/account/fee/
     * currency — the same double-charge/replay threat the idempotency key
     * (`fee-{cycleId}-{accountId}-{feeId}-{currency}`) is designed to prevent, checked here as a
     * global conservation law rather than a per-request property. A waived or zero-amount fee
     * assesses `0` and posts nothing, so it already satisfies the equality without a separate
     * case; a fee stuck PENDING (outbox not yet dispatched) would also fail this until it lands,
     * which is correct — the invariant is checked after the scheduler drains, i.e. once a step
     * has fully settled (mirrors [settlementCompleteness]/[sepaPaymentCompleteness]'s "settled"
     * semantics, not "eventually consistent mid-flight").
     */
    val billingFeeConservation = object : Invariant {
        override val name = "billing-fee-conservation"
        override fun check(world: World): Violation? {
            world.billingFees.keys().forEach { key ->
                val assessedAmount = world.billingFees.assessedAmount(key)
                val postedAmount = world.billingFees.postedAmount(key)
                if (assessedAmount.compareTo(postedAmount) != 0) {
                    return Violation(
                        name,
                        "fee $key assessed=$assessedAmount but posted=$postedAmount",
                    )
                }
            }
            return null
        }
    }

    /**
     * ADR-0033 / issue #667: the credit-interest conservation laws, per `(account, currency)`,
     * checked once a step has fully settled (the outbox redrive drains first — same "settled"
     * semantics as [billingFeeConservation]):
     *
     * 1. `Σ capitalized gross ≤ Σ accrued` — a haléř that never accrued must never capitalize;
     * 2. `gross == net + tax` — the statutory whole-CZK rounding inside `WithholdingTaxPolicy`
     *    (§36/§38d) must split the gross *exactly*, never create or destroy money;
     * 3. `Σ capitalized net == Σ net journal legs posted` and `Σ tax withheld == Σ tax legs
     *    posted` — every capitalization landed in the ledger, on BOTH legs. Posting the net to
     *    the customer but dropping the tax-payable leg (the classic ADR-0033 defect class:
     *    conservation-of-money still holds because the journal balances per entry) is exactly
     *    what the per-leg equality catches.
     */
    val interestCapitalizationConservation = object : Invariant {
        override val name = "interest-capitalization-conservation"
        override fun check(world: World): Violation? {
            world.interest.keys().forEach { key ->
                val accrued = world.interest.accruedAmount(key)
                val gross = world.interest.capitalizedGrossAmount(key)
                val net = world.interest.capitalizedNetAmount(key)
                val tax = world.interest.taxWithheldAmount(key)
                if (gross > accrued) {
                    return Violation(name, "account $key capitalized $gross > accrued $accrued")
                }
                if (gross.compareTo(net + tax) != 0) {
                    return Violation(name, "account $key gross=$gross but net=$net + tax=$tax")
                }
                val postedNet = world.interest.postedNetAmount(key)
                if (net.compareTo(postedNet) != 0) {
                    return Violation(name, "account $key capitalized net=$net but posted net=$postedNet")
                }
                val postedTax = world.interest.postedTaxAmount(key)
                if (tax.compareTo(postedTax) != 0) {
                    return Violation(name, "account $key withheld tax=$tax but posted tax=$postedTax")
                }
            }
            return null
        }
    }

    /**
     * ADR-0035 §E / ADR-0078, issue #667: a statement period is persisted *if and only if* the
     * real `ReconciliationPolicy` decided `Reconciled` for that close attempt — never on a
     * `Mismatch` (no partial, self-inconsistent legal document is ever produced) and never skipped
     * on a `Reconciled` decision (no silently-dropped good close either).
     */
    val statementCloseIntegrity = object : Invariant {
        override val name = "statement-close-integrity"
        override fun check(world: World): Violation? {
            world.statementCloses.attempts().forEach { attempt ->
                val reconciled = world.statementCloses.wasReconciled(attempt)
                val persisted = world.statementCloses.wasPersisted(attempt)
                if (reconciled != persisted) {
                    return Violation(name, "close attempt $attempt: reconciled=$reconciled but persisted=$persisted")
                }
            }
            return null
        }
    }

    /**
     * The standing-order execution invariant: the REAL `StandingOrder` status machine's counters
     * must agree, per order, with what actually happened on the money path, once a step has fully
     * settled —
     *
     * 1. `executionCount == confirmed + attempted failures` — the sweep claims the execution
     *    slot BEFORE the attempt (`recordExecution` advances `nextExecutionDate` and the counter
     *    up front, by design), so every claimed slot must have exactly one recorded outcome:
     *    money confirmed, or a failed attempt. A claimed slot with neither is the defect class
     *    this catches;
     * 2. `failureCount == the machine's current failure streak` — the production consumer records
     *    a failure through `runCatching` (`recordFailureSafely`), and a confirmed execution resets
     *    the real `failureCount` to 0 (`confirmExecution`); the book mirrors both sides, so a
     *    failure the machine never accepted (a gap) or a reset the machine never performed reads
     *    as a violation, while the legitimate fail → confirm → fail streak lifecycle stays green;
     * 3. FAILED implies the real `MAX_CONSECUTIVE_FAILURES` streak was reached, and COMPLETED
     *    implies the lifecycle contract held (ONCE: exactly one confirmed execution; recurring:
     *    the next execution date has moved past the end date).
     */
    val standingOrderCompleteness = object : Invariant {
        override val name = "standing-order-completeness"
        override fun check(world: World): Violation? {
            world.standingOrders.forEach { order ->
                val accounted = world.standingOrdersBook.confirmedCount(order.id) +
                    world.standingOrdersBook.attemptedFailureCount(order.id)
                if (order.executionCount != accounted) {
                    return Violation(
                        name,
                        "order ${order.id} executionCount=${order.executionCount} but confirmed+failures=$accounted",
                    )
                }
                if (order.failureCount != world.standingOrdersBook.machineStreak(order.id)) {
                    return Violation(
                        name,
                        "order ${order.id} failureCount=${order.failureCount} but machine streak=" +
                            world.standingOrdersBook.machineStreak(order.id),
                    )
                }
                if (order.status == StandingOrderStatus.FAILED &&
                    order.failureCount < StandingOrder.MAX_CONSECUTIVE_FAILURES
                ) {
                    return Violation(name, "order ${order.id} FAILED after only ${order.failureCount} failures")
                }
                if (order.status == StandingOrderStatus.COMPLETED) {
                    // ONCE legitimately retries a failed single execution (recordExecution keeps
                    // it ACTIVE so the next sweep re-picks it), so executionCount may exceed 1;
                    // the contract that matters is exactly one CONFIRMED execution. A recurring
                    // order completes when recordExecution moved its next date past the end date.
                    val complete = if (order.frequency == Frequency.ONCE) {
                        world.standingOrdersBook.confirmedCount(order.id) == 1
                    } else {
                        order.endDate != null && order.nextExecutionDate.isAfter(order.endDate)
                    }
                    if (!complete) {
                        return Violation(name, "order ${order.id} COMPLETED but lifecycle contract unmet")
                    }
                }
            }
            return null
        }
    }

    /**
     * Outgoing-SWIFT completeness: every `SwiftMessage` the scenario created must reach a
     * terminal status (COMPLETED / REJECTED / FAILED) once the step has settled — none may
     * remain mid-flight in PENDING/VALIDATED/SENT, the states where a crash would leave the
     * operator guessing whether the money left. Same "settled" semantics as
     * [settlementCompleteness]: checked after the scheduler drains.
     */
    val swiftCompleteness = object : Invariant {
        private val terminal = setOf(SwiftStatus.COMPLETED, SwiftStatus.REJECTED, SwiftStatus.FAILED)

        override val name = "swift-completeness"
        override fun check(world: World): Violation? {
            world.swiftMessages.forEach { message ->
                if (message.status !in terminal) {
                    return Violation(name, "swift message ${message.id} stuck in ${message.status}")
                }
            }
            return null
        }
    }

    /**
     * ADR-0036 §B / ADR-0100 full-service adoption: the SDD collection invariant, reconciling the
     * REAL `SddMandate` lifecycle against the money-path book, once a step has fully settled:
     *
     * 1. a mandate with a recorded collection (book side) must carry the matching stamp on the
     *    aggregate itself (`lastCollectionDate`, and FRST advanced to RCUR by the real
     *    `MandateLifecycle.recordCollection`) — a collection the lifecycle never recorded is the
     *    defect class this catches, and an always-empty book would let it pass vacuously;
     * 2. the book's last collection date must equal the mandate's own `lastCollectionDate` — the
     *    two sides are written independently by the scenario, so drift between them means one
     *    side lost an outcome;
     * 3. no live (ACTIVE/SUSPENDED) mandate may be idle past the EPC 36-month auto-expiry — the
     *    step's `expireIfIdle` precondition must actually fire, never leave a stale mandate
     *    collectable.
     */
    val sddCollectionCompleteness = object : Invariant {
        override val name = "sdd-collection-completeness"
        override fun check(world: World): Violation? {
            world.sddMandates.forEach { mandate ->
                val posted = world.sddCollections.postedCollections(mandate.id)
                // World creates mandates without prior collections; its book covers their
                // complete simulated lifecycle. Imported history would need an explicit book
                // baseline, never silent inference from an aggregate stamp.
                if (posted == 0 && mandate.lastCollectionDate != null) {
                    return Violation(name, "mandate ${mandate.id} has a lifecycle stamp but no recorded collection")
                }
                if (posted > 0) {
                    if (mandate.lastCollectionDate == null) {
                        return Violation(name, "mandate ${mandate.id} has $posted collections but no lifecycle stamp")
                    }
                    if (mandate.sequenceType == SequenceType.FRST) {
                        return Violation(name, "mandate ${mandate.id} collected but still FRST")
                    }
                    val bookDate = world.sddCollections.lastCollectionDate(mandate.id)
                    if (bookDate != mandate.lastCollectionDate) {
                        return Violation(
                            name,
                            "mandate ${mandate.id} stamped ${mandate.lastCollectionDate} but book has $bookDate",
                        )
                    }
                }
                if (mandate.status == MandateStatus.ACTIVE || mandate.status == MandateStatus.SUSPENDED) {
                    val asOf = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
                    if (MandateLifecycle.isIdle(MandateLifecycle.lastActivity(mandate), asOf)) {
                        return Violation(
                            name,
                            "mandate ${mandate.id} idle past ${MandateLifecycle.IDLE_EXPIRY_MONTHS}m but still ${mandate.status}",
                        )
                    }
                }
            }
            return null
        }
    }

    /**
     * Outgoing-SWIFT conservation: a COMPLETED MT103 must have posted exactly its amount, and
     * nothing else may post under a SWIFT message id — so a message that completed without
     * money leaving (or money that left without a completion) is caught per message, while a
     * REJECTED (validate refused) or FAILED (compensated) message provably posted nothing.
     */
    val swiftMoneyConservation = object : Invariant {
        override val name = "swift-money-conservation"
        override fun check(world: World): Violation? {
            world.swiftMessages.forEach { message ->
                val posted = world.swiftBook.postedAmount(message.id)
                if (message.status == SwiftStatus.COMPLETED) {
                    val amount = message.amount.amount
                    if (posted.compareTo(amount) != 0) {
                        return Violation(name, "swift ${message.id} completed but posted=$posted amount=$amount")
                    }
                } else if (posted.signum() != 0) {
                    return Violation(name, "swift ${message.id} is ${message.status} but posted=$posted")
                }
            }
            world.swiftBook.postedIds().forEach { messageId ->
                if (world.swiftMessages.none { it.id == messageId }) {
                    return Violation(name, "posted under unknown swift message $messageId")
                }
            }
            return null
        }
    }

    /** All Layer-3 invariants, in check order. */
    val ALL: List<Invariant> = listOf(
        conservationOfMoney,
        noNegativeBalance,
        projectionConsistency,
        compensationCompleteness,
        auditCompleteness,
        sepaPaymentCompleteness,
        settlementCompleteness,
        billingFeeConservation,
        interestCapitalizationConservation,
        statementCloseIntegrity,
        standingOrderCompleteness,
        sddCollectionCompleteness,
        swiftCompleteness,
        swiftMoneyConservation,
    )

    /** A terminal-state guard used by the saga orchestrator. */
    fun isTerminal(state: SagaState): Boolean =
        state == SagaState.COMPLETED || state == SagaState.COMPENSATED || state == SagaState.FAILED
}
