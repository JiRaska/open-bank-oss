// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.delegation.domain.model.SpendWindows
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.libs.domain.money.Money
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.DelegationSpendBook
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import com.openbank.swift.domain.model.SwiftStatus
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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
     * Issue #14366 (Test Intelligence simulation gap, money path): every `DomesticPayment`
     * driven by [com.openbank.simulation.scenario.DomesticClearingScenario] must reach a
     * terminal status (SETTLED / REJECTED / RETURNED / CANCELLED) by the time a step has fully
     * settled — the domestic analogue of [sepaPaymentCompleteness]. A payment left RECEIVED
     * (never validated), VALIDATED (never dispatched), or SENT_TO_CLEARING (never settled nor
     * returned) is the silent money-in-flight defect this invariant exists to surface.
     */
    val domesticPaymentCompleteness = object : Invariant {
        private val terminal = setOf(
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.RETURNED,
            DomesticPaymentStatus.CANCELLED,
        )

        override val name = "domestic-payment-completeness"
        override fun check(world: World): Violation? {
            world.domesticPayments.forEach { payment ->
                if (payment.status !in terminal) {
                    return Violation(name, "domestic payment ${payment.id} stuck in ${payment.status}")
                }
            }
            return null
        }
    }

    /**
     * Issue #14366 (Test Intelligence simulation gap, money path): every `ClearingBatch` and
     * `ClearingItem` driven by [com.openbank.simulation.scenario.ClearingCycleScenario] must
     * reach a terminal status (SETTLED / FAILED / REVERSED) by the time a step has fully
     * settled — a batch left IN_CLEARING is the silent money-in-flight defect this layer
     * exists to surface. On top of completeness, the netting conservation cross-checks the
     * batch against its own items with the kernel Money: the batch total must still equal the
     * sum of the item amounts that were batched, and totalCredit/netPosition must hold the
     * bilateral offset (totalCredit == totalDebit, netPosition == totalDebit - totalCredit)
     * production's fold produces — a batch whose totals drifted from its items fails here.
     */
    val clearingCompleteness = object : Invariant {
        private val terminal = setOf(
            ClearingStatus.SETTLED,
            ClearingStatus.FAILED,
            ClearingStatus.REVERSED,
        )

        override val name = "clearing-completeness"
        override fun check(world: World): Violation? {
            world.clearingBatches.forEach { batch ->
                if (batch.status !in terminal) {
                    return Violation(name, "clearing batch ${batch.id} stuck in ${batch.status}")
                }
                val itemSum = world.clearingItems
                    .filter { it.batchId == batch.id }
                    .fold(Money.zero(batch.currency)) { acc, item -> acc + Money.of(item.amount, item.currency) }
                if (itemSum.amount.compareTo(batch.totalDebit) != 0) {
                    return Violation(name, "clearing batch ${batch.id} totalDebit != sum of its items")
                }
                if (batch.totalCredit.compareTo(batch.totalDebit) != 0) {
                    return Violation(name, "clearing batch ${batch.id} totalCredit != totalDebit")
                }
                if (batch.netPosition.compareTo(batch.totalDebit.subtract(batch.totalCredit)) != 0) {
                    return Violation(name, "clearing batch ${batch.id} netPosition != totalDebit - totalCredit")
                }
            }
            world.clearingItems.forEach { item ->
                if (item.status !in terminal) {
                    return Violation(name, "clearing item ${item.id} stuck in ${item.status}")
                }
            }
            return null
        }
    }

    /**
     * Issue #14366 (Test Intelligence simulation gap, money path): every `SctInstPayment`
     * driven by [com.openbank.simulation.scenario.SctInstSettlementScenario] must reach a
     * terminal status (SETTLED / REJECTED / RECALLED / TIMEOUT) by the time a step has fully
     * settled — the instant-payment analogue of [sepaPaymentCompleteness]. A payment left
     * PENDING (screening hold that never decided) or PROCESSING (scheme submission that never
     * settled) is the silent money-in-flight defect this invariant exists to surface.
     */
    val sctInstPaymentCompleteness = object : Invariant {
        private val terminal = setOf(
            SctInstStatus.SETTLED,
            SctInstStatus.REJECTED,
            SctInstStatus.RECALLED,
            SctInstStatus.TIMEOUT,
        )

        override val name = "sct-inst-payment-completeness"
        override fun check(world: World): Violation? {
            world.sctInstPayments.forEach { payment ->
                if (payment.status !in terminal) {
                    return Violation(name, "sct-inst payment ${payment.id} stuck in ${payment.status}")
                }
            }
            return null
        }
    }

    /**
     * Issue #14366 (Test Intelligence simulation gap, money path): every `CardAuthorization`
     * driven by [com.openbank.simulation.scenario.CardClearingScenario] must be internally
     * consistent and never a stale hold — (1) `clearedAmountMinorUnits` never exceeds the
     * authorised amount, (2) a decline carries its reason exactly when the status is DECLINED
     * (the status exists to keep a refused customer answerable from the row), and (3) no hold
     * outlives its own `expiresAt`: an APPROVED / PARTIALLY_CLEARED authorisation whose expiry
     * has passed is a permanent freeze on money the customer cannot spend — the silent
     * money-in-flight defect this invariant exists to surface. A hold still inside its window
     * is legitimate and passes.
     */
    val cardAuthorizationCompleteness = object : Invariant {
        override val name = "card-authorization-completeness"
        override fun check(world: World): Violation? {
            val now = world.context.clock.instant()
            world.cardAuthorizations.forEach { authorization ->
                if (authorization.clearedAmountMinorUnits > authorization.amountMinorUnits) {
                    return Violation(
                        name,
                        "card ${authorization.id} cleared ${authorization.clearedAmountMinorUnits}" +
                            " of ${authorization.amountMinorUnits}",
                    )
                }
                if ((authorization.status == AuthorizationStatus.DECLINED) != (authorization.declineReason != null)) {
                    return Violation(
                        name,
                        "card ${authorization.id} status ${authorization.status} with declineReason" +
                            " ${authorization.declineReason}",
                    )
                }
                if (authorization.status.holdsFunds && now.isAfter(authorization.expiresAt)) {
                    return Violation(
                        name,
                        "card ${authorization.id} holds ${authorization.heldAmountMinorUnits}" +
                            " past its expiresAt ${authorization.expiresAt}",
                    )
                }
            }
            return null
        }
    }

    /**
     * Issue #14366 (Test Intelligence simulation gap, money path): per authorisation, the Σ of
     * presentment amounts the scenario posted through the REAL `AuthorizationLifecycle.clear`
     * must equal the authorisation's own `clearedAmountMinorUnits` — a clearing that posted
     * twice, or a presentment the aggregate silently lost, breaks the equality for exactly the
     * authorisation it belongs to. Posted amounts are keyed per authorisation (same convention
     * as [swiftMoneyConservation]), so a reversal or an unrelated posting cannot mask a gap.
     */
    val cardClearingConservation = object : Invariant {
        override val name = "card-clearing-conservation"
        override fun check(world: World): Violation? {
            world.cardClearings.postedIds().forEach { authorizationId ->
                val authorization: CardAuthorization? =
                    world.cardAuthorizations.firstOrNull { it.id == authorizationId }
                val posted = world.cardClearings.postedAmount(authorizationId)
                if (authorization == null || authorization.clearedAmountMinorUnits != posted) {
                    return Violation(
                        name,
                        "card $authorizationId posted $posted but cleared" +
                            " ${authorization?.clearedAmountMinorUnits ?: "unknown authorization"}",
                    )
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

    /**
     * ADR-0249 D3: a delegated spend that moved money must be CONFIRMED, and one that did not
     * must be RELEASED — a reservation still RESERVED after the step settled means an exit path
     * (decline, compensation, seeded release, confirm) was silently dropped, leaving headroom
     * consumed forever (the leak ADR-0249's consequences section names). Same "settled"
     * semantics as [settlementCompleteness]: checked after the scheduler drains.
     */
    val delegatedSpendCompleteness = object : Invariant {
        override val name = "delegated-spend-completeness"
        override fun check(world: World): Violation? {
            world.spendReservations.forEach { reservation ->
                if (reservation.state == SpendReservationState.RESERVED) {
                    return Violation(name, "spend reservation ${reservation.id} stuck in RESERVED")
                }
            }
            return null
        }
    }

    /**
     * ADR-0249 D3: per grant, Σ CONFIRMED reservation amounts == Σ ledger journals posted for
     * delegated spend. A confirmation whose journal never posted inflates the delegate's counted
     * spend without moving money; a posted journal with no confirmation spends the grantor's
     * money while the ceiling still counts it — both are caught per grant, not fleet-wide.
     */
    val delegatedSpendConservation = object : Invariant {
        override val name = "delegated-spend-conservation"
        override fun check(world: World): Violation? {
            val entriesByTransaction = world.ledger.entriesByTransaction()
            val grants = world.delegationGrants.associateBy { it.id }
            world.spendReservations.forEach { reservation ->
                val grant = grants[reservation.grantId]
                    ?: return Violation(name, "reservation ${reservation.id} has no grant")
                reservationLedgerViolation(
                    world,
                    reservation,
                    grant,
                    entriesByTransaction[reservation.id].orEmpty(),
                )?.let { return it }
            }
            world.delegationSpends.grantIds().forEach { grantId ->
                val confirmed = world.delegationSpends.confirmedAmount(grantId)
                val posted = world.delegationSpends.postedAmount(grantId)
                if (confirmed.compareTo(posted) != 0) {
                    return Violation(name, "grant $grantId confirmed=$confirmed but posted=$posted")
                }
            }
            return null
        }

        private fun reservationLedgerViolation(
            world: World,
            reservation: SpendReservation,
            grant: DelegationGrant,
            entries: List<JournalEntry>,
        ): Violation? {
            val actual = mutableMapOf<Triple<UUID, UUID?, String>, BigDecimal>()
            entries.forEach { entry ->
                entry.lines.forEach { line ->
                    val key = Triple(line.glAccountId, line.subAccountId, line.baseAmount.currency.code)
                    val signed = if (line.side == JournalSide.CREDIT) {
                        line.baseAmount.amount
                    } else {
                        line.baseAmount.amount.negate()
                    }
                    actual.merge(key, signed, BigDecimal::add)
                }
            }
            val net = actual.filterValues { it.signum() != 0 }
            val expected = if (reservation.state == SpendReservationState.CONFIRMED) {
                val target = world.customerAccounts.first { it != grant.resourceId }
                mapOf(
                    Triple(UUID(0L, 1L), grant.resourceId, reservation.amount.currency.code) to
                        reservation.amount.amount.negate(),
                    Triple(UUID(0L, 1L), target, reservation.amount.currency.code) to reservation.amount.amount,
                )
            } else {
                emptyMap()
            }
            if (net.keys != expected.keys ||
                expected.any { (key, amount) -> net.getValue(key).compareTo(amount) != 0 }
            ) {
                return Violation(name, "reservation ${reservation.id} actual ledger legs disagree with its state")
            }
            return null
        }
    }

    /**
     * ADR-0249 D3: replay every ceiling decision the scenario recorded. An ALLOWED decision must
     * pass all three ceilings under the exact [CountedSpend] the policy was asked with
     * (per-transaction, daily, monthly — each when the grant carries it); a REFUSED decision must
     * breach at least one (a refusal with no breached ceiling is the check-evaluate disagreement
     * the ADR forbids). A scenario that reserved against a stale or invented counted value fails
     * here even when the money side still conserves.
     */
    val delegationCeilingIntegrity = object : Invariant {
        override val name = "delegation-ceiling-integrity"
        override fun check(world: World): Violation? {
            world.delegationSpends.grantIds().forEach { grantId ->
                val grant = world.delegationGrants.firstOrNull { it.id == grantId } ?: return@forEach
                world.delegationSpends.decisionsFor(grantId).forEach { decision ->
                    replayDecision(name, grant, decision)?.let { return it }
                }
            }
            return null
        }

        /**
         * Replay one recorded decision against the grant's real limits: an ALLOWED decision must
         * pass all three ceilings under the exact [CountedSpend] the policy was asked with; a
         * REFUSED decision must breach at least one (a refusal with no breached ceiling is the
         * check-evaluate disagreement ADR-0249 forbids).
         */
        private fun replayDecision(
            invariantName: String,
            grant: DelegationGrant,
            decision: DelegationSpendBook.Decision,
        ): Violation? {
            val window = SpendWindows.windowAt(decision.history.at)
            val relevant = decision.history.reservations.filter {
                it.grantId == grant.id &&
                    it.amount.currency.code == decision.currency &&
                    it.state != SpendReservationState.RELEASED &&
                    !it.createdAt.isAfter(decision.history.at)
            }
            val day = relevant
                .filter { !it.createdAt.isBefore(window.dayStart) }
                .fold(BigDecimal.ZERO) { total, reservation -> total + reservation.amount.amount }
            val month = relevant
                .filter { !it.createdAt.isBefore(window.monthStart) }
                .fold(BigDecimal.ZERO) { total, reservation -> total + reservation.amount.amount }
            val currencyMismatch = decision.counted.withinDay.currency.code != decision.currency ||
                decision.counted.withinMonth.currency.code != decision.currency
            if (
                currencyMismatch ||
                decision.counted.withinDay.amount.compareTo(day) != 0 ||
                decision.counted.withinMonth.amount.compareTo(month) != 0
            ) {
                return Violation(invariantName, "grant ${grant.id} counted spend disagrees with pre-decision history")
            }
            val breaches = mutableListOf<String>()
            grant.perTransactionLimit?.let { limit ->
                if (decision.amount.compareTo(limit.amount) > 0) breaches += "per-transaction"
            }
            grant.dailyLimit?.let { limit ->
                if (decision.counted.withinDay.amount.add(decision.amount).compareTo(limit.amount) > 0) {
                    breaches += "daily"
                }
            }
            grant.monthlyLimit?.let { limit ->
                if (decision.counted.withinMonth.amount.add(decision.amount).compareTo(limit.amount) > 0) {
                    breaches += "monthly"
                }
            }
            if (!decision.refused && breaches.isNotEmpty()) {
                return Violation(
                    invariantName,
                    "grant ${grant.id} allowed ${decision.amount} that breaches ${breaches.joinToString()}",
                )
            }
            if (decision.refused && breaches.isEmpty()) {
                return Violation(
                    invariantName,
                    "grant ${grant.id} refused ${decision.amount} under recorded counted spend with no breached ceiling",
                )
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
        domesticPaymentCompleteness,
        clearingCompleteness,
        sctInstPaymentCompleteness,
        cardAuthorizationCompleteness,
        cardClearingConservation,
        billingFeeConservation,
        interestCapitalizationConservation,
        statementCloseIntegrity,
        standingOrderCompleteness,
        sddCollectionCompleteness,
        delegatedSpendCompleteness,
        delegatedSpendConservation,
        delegationCeilingIntegrity,
        swiftCompleteness,
        swiftMoneyConservation,
    )

    /** A terminal-state guard used by the saga orchestrator. */
    fun isTerminal(state: SagaState): Boolean =
        state == SagaState.COMPLETED || state == SagaState.COMPENSATED || state == SagaState.FAILED
}
