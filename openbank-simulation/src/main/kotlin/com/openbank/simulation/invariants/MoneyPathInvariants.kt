// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.ScreeningRecord
import com.openbank.simulation.runner.World
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.StandingOrderStatus
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal

/**
 * The ADR-0100 Layer-3 invariant set for the money path. Each is a pure function of the
 * [World] state and is checked after every step; the first to break aborts the run with a
 * reproducible seed.
 */
object MoneyPathInvariants {

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
     * ADR-0032 §B: the real `ScreeningPolicy`'s verdict over a conversion's screened names and the
     * payment path's disposition of that conversion must never disagree. A BLOCK must refuse and
     * never execute; a REVIEW alert must be resolved exactly once — false positive (released,
     * then executed) or confirmed hit (escalated, refused, never executed) — never silently
     * dropped, never double-resolved; a CLEAR must execute and never be refused. A conversion that
     * executed under a BLOCK, or a REVIEW alert that vanished without a resolution, is exactly the
     * class of defect a sanctions-path audit exists to catch.
     */
    val screeningDispositionIntegrity = object : Invariant {
        override val name = "screening-disposition-integrity"
        override fun check(world: World): Violation? {
            world.screening.conversions().forEach { conversionId ->
                val r = world.screening.record(conversionId) ?: return@forEach
                dispositionDisagreement(r)?.let { detail ->
                    return Violation(name, "conversion $conversionId: $detail")
                }
            }
            return null
        }
    }

    /**
     * The per-conversion half of [screeningDispositionIntegrity] — extracted so `check` stays
     * within the nesting budget. Returns the first disagreement between the decision and the
     * disposition, or null when they match.
     */
    private fun dispositionDisagreement(r: ScreeningRecord): String? = when (r.decision) {
        ScreeningDecision.BLOCK -> when {
            r.executed -> "BLOCK but executed"
            !r.refused -> "BLOCK never refused"
            else -> null
        }
        ScreeningDecision.REVIEW -> reviewDisagreement(r)
        ScreeningDecision.CLEAR -> when {
            r.refused -> "CLEAR but refused"
            !r.executed -> "CLEAR but never executed"
            else -> null
        }
    }

    private fun reviewDisagreement(r: ScreeningRecord): String? = when {
        r.resolutionCount == 0 -> "REVIEW alert silently dropped"
        r.resolutionCount > 1 -> "REVIEW alert resolved ${r.resolutionCount} times"
        !r.reviewWasOpened -> "REVIEW was resolved without ever opening an alert"
        r.reviewOpen || !r.reviewResolved ->
            "REVIEW is not settled (open=${r.reviewOpen}, resolved=${r.reviewResolved})"
        r.reviewEscalatedToBlock && (!r.refused || r.executed) ->
            "escalated hit must refuse and never execute (refused=${r.refused}, executed=${r.executed})"
        !r.reviewEscalatedToBlock && r.refused -> "false positive released but refused"
        !r.reviewEscalatedToBlock && !r.executed -> "false positive released but never executed"
        else -> null
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
     * Issue #12839 follow-up (Refs #12830): the standing-order execution conservation laws, per
     * order, checked once a step has fully settled (same "settled" semantics as
     * [billingFeeConservation]):
     *
     * 1. `Σ posted == |confirmed dates| × order amount` — every confirmed execution date produced
     *    exactly one payment journal of exactly the order amount, and no other execution did. A
     *    replayed/duplicated due event, or a failure that moved money anyway, breaks the equality
     *    (the standing-order analogue of ADR-0143's fee conservation);
     * 2. ONCE discipline: a one-off order attempts at most ONE distinct execution date (its date
     *    never advances), and once its single execution is confirmed the order must be COMPLETED
     *    — the exact lifecycle the domain aggregate's `confirmExecution` guarantees and the
     *    service layer regressed on in issue #12839 (a confirmed ONCE order left ACTIVE is
     *    re-due forever). A failed-then-retried ONCE order stays within law 2 as long as the
     *    retry replays the SAME date (idempotent) rather than minting a new one.
     */
    val standingOrderExecutionConservation = object : Invariant {
        override val name = "standing-order-execution-conservation"
        override fun check(world: World): Violation? {
            world.standingOrders.all().forEach { order ->
                val amount = BigDecimal(order.amountMinorUnits).movePointLeft(2)
                val view = world.standingOrders.view(order.id)
                val expected = amount.multiply(BigDecimal.valueOf(view.confirmedDates.size.toLong()))
                if (view.postedAmount.compareTo(expected) != 0) {
                    return Violation(
                        name,
                        "order ${order.id} posted=${view.postedAmount} but " +
                            "${view.confirmedDates.size} confirmed executions of $amount",
                    )
                }
                if (order.frequency == Frequency.ONCE) {
                    if (view.attemptedDates.size > 1) {
                        return Violation(
                            name,
                            "ONCE order ${order.id} attempted on more than one date: ${view.attemptedDates}",
                        )
                    }
                    if (view.confirmedDates.isNotEmpty() && order.status != StandingOrderStatus.COMPLETED) {
                        return Violation(
                            name,
                            "ONCE order ${order.id} confirmed its single execution but is ${order.status}, " +
                                "expected COMPLETED",
                        )
                    }
                }
            }
            return null
        }
    }

    /**
     * The card-authorization hold conservation laws, per authorization, checked once a step has
     * fully settled (the expiry task fires in the drain first — same "settled" semantics as
     * [billingFeeConservation]):
     *
     * 1. no authorization may still [hold funds][com.openbank.cardprocessing.domain.model.AuthorizationStatus.holdsFunds]
     *    — a hold that never reaches clearing, reversal or expiry is a permanent freeze the
     *    customer cannot spend and cannot see released;
     * 2. `cleared == posted` — every presentment the policy accepted landed exactly once as a
     *    ledger journal: a dropped capture posting (money the scheme thinks it collected but
     *    the ledger never received) and a replayed one (double-posted on a re-delivered
     *    presentment) both break the equality, and conservation-of-money alone cannot see
     *    either, because each journal still balances internally;
     * 3. `cleared + released == authorized` — a hold is fully accounted for: what was not
     *    captured must have been released back to available balance, no less (silently frozen
     *    funds) and no more (money freed while the scheme can still present against it).
     */
    val cardAuthorizationConservation = object : Invariant {
        override val name = "card-authorization-conservation"
        override fun check(world: World): Violation? {
            world.cardAuths.all().forEach { authorization ->
                if (authorization.status.holdsFunds) {
                    return Violation(
                        name,
                        "authorization ${authorization.id} still holds funds in ${authorization.status}",
                    )
                }
                val cleared = authorization.clearedAmountMinorUnits
                val posted = world.cardAuths.postedMinorUnits(authorization.id)
                if (cleared != posted) {
                    return Violation(
                        name,
                        "authorization ${authorization.id} cleared=$cleared but posted=$posted",
                    )
                }
                val released = world.cardAuths.releasedMinorUnits(authorization.id)
                if (authorization.status == AuthorizationStatus.DECLINED && (cleared != 0L || released != 0L)) {
                    return Violation(
                        name,
                        "declined authorization ${authorization.id}: cleared=$cleared, posted=$posted, " +
                            "released=$released despite never holding funds",
                    )
                }
                // A DECLINED authorization never held funds: the authorized amount is what the
                // acquirer asked for, not what the bank reserved, so there is nothing to
                // account for beyond the zero cleared, posted and released totals checked above.
                if (authorization.status != AuthorizationStatus.DECLINED &&
                    cleared + released != authorization.amountMinorUnits
                ) {
                    return Violation(
                        name,
                        "authorization ${authorization.id}: cleared=$cleared + released=$released " +
                            "!= authorized=${authorization.amountMinorUnits}",
                    )
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
        standingOrderExecutionConservation,
        screeningDispositionIntegrity,
        cardAuthorizationConservation,
    )

    /** A terminal-state guard used by the saga orchestrator. */
    fun isTerminal(state: SagaState): Boolean =
        state == SagaState.COMPLETED || state == SagaState.COMPENSATED || state == SagaState.FAILED
}
