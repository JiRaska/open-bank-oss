// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.ClearingSettlementPosting
import com.openbank.simulation.model.SddCollectionStatus
import com.openbank.simulation.runner.World
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
     * Money-path SCT Inst adoption: every `SctInstPayment` driven by
     * [com.openbank.simulation.scenario.SctInstPaymentScenario] must reach a terminal status
     * (SETTLED / REJECTED / TIMEOUT / RECALLED) by the time a step has fully settled — the
     * instant-payment analogue of [sepaPaymentCompleteness]. A payment stuck PENDING or
     * PROCESSING after the drain means a timeout or recall path failed to fire, exactly the
     * "mid-flight money" defect class the scenario's fault injection exists to surface.
     */
    val sctInstCompleteness = object : Invariant {
        private val terminal = setOf(
            SctInstStatus.SETTLED,
            SctInstStatus.REJECTED,
            SctInstStatus.TIMEOUT,
            SctInstStatus.RECALLED,
        )

        override val name = "sct-inst-completeness"
        override fun check(world: World): Violation? {
            world.sctInstPayments.forEach { payment ->
                if (payment.status !in terminal) {
                    return Violation(name, "sct-inst payment ${payment.paymentId} stuck in ${payment.status}")
                }
            }
            return null
        }
    }

    /**
     * Money-path clearing adoption: every `ClearingItem` the scenario seeded is either terminal
     * (SETTLED / FAILED / REVERSED) or accounted for, and every `ClearingBatch` is terminal. The
     * accounted-for case is the #11974 reported-not-dropped law: an item still PENDING whose rail
     * HAS cycled since it was seeded must be counted in that rail's latest cycle result
     * `unsettleablePending` (its currency has no settlement GL pair — reported, never silently
     * skipped). An item whose rail has not cycled since seeding is legitimately PENDING (the
     * #12004 rail-isolation probe), and a railless item can never be selected by any cycle.
     */
    val clearingCompleteness = object : Invariant {
        private val terminal = setOf(
            ClearingStatus.SETTLED,
            ClearingStatus.FAILED,
            ClearingStatus.REVERSED,
        )

        override val name = "clearing-completeness"
        override fun check(world: World): Violation? {
            world.clearing.batches.forEach { batch ->
                if (batch.status !in terminal) {
                    return Violation(name, "clearing batch ${batch.id} stuck in ${batch.status}")
                }
            }
            world.clearing.items.forEach { item ->
                if (item.status in terminal) return@forEach
                val rail = item.rail
                    ?: return Violation(
                        name,
                        "clearing item ${item.id} has no rail: no cycle can ever select it (#12004)",
                    )
                if (world.clearing.cycleEpoch(rail) == world.clearing.seedEpochOf(item.id)) {
                    return@forEach // the rail has not cycled since the item was seeded — PENDING is expected
                }
                val last = world.clearing.cycles.lastOrNull { it.rail == rail }
                if (last == null || last.unsettleablePending[item.currency] == null) {
                    return Violation(
                        name,
                        "clearing item ${item.id} (${item.currency} on $rail) was skipped by cycle " +
                            "${last?.cycleId}: not batched, not terminal, not reported unsettleable",
                    )
                }
            }
            return null
        }
    }

    /**
     * Money-path clearing adoption: the batch bookkeeping law, per batch. A SETTLED batch has
     * exactly its items (itemCount, Σ amounts == totalDebit == totalCredit, zero net position —
     * the real NET bilateral model) and exactly one net-settlement journal for totalDebit; a
     * FAILED batch moved no money (no journal — a settlement leg for a dead batch would be the
     * classic "money without a clearing" defect); a REVERSED batch's journal was unwound. The
     * cross-entry drift itself is covered by conservationOfMoney — this invariant pins the
     * batch-to-ledger correspondence the clearing cycle's durable outbox intent (ADR-0281)
     * promises.
     */
    val clearingBatchConservation = object : Invariant {
        override val name = "clearing-batch-conservation"
        override fun check(world: World): Violation? {
            world.clearing.batches.forEach { batch ->
                val items = world.clearing.itemsOf(batch.id)
                val posting = world.clearing.postings[batch.id]
                when (batch.status) {
                    ClearingStatus.SETTLED -> return checkSettled(batch, items, posting)
                    ClearingStatus.FAILED -> {
                        if (posting != null) {
                            return Violation(
                                name,
                                "FAILED batch ${batch.id} posted a settlement journal: money moved for a dead batch",
                            )
                        }
                        if (items.any { it.status != ClearingStatus.FAILED }) {
                            return Violation(name, "FAILED batch ${batch.id} has non-FAILED items")
                        }
                    }
                    ClearingStatus.REVERSED -> {
                        if (posting == null || !posting.reversed) {
                            return Violation(
                                name,
                                "REVERSED batch ${batch.id}: settlement journal not reversed (money not unwound)",
                            )
                        }
                        if (items.any { it.status != ClearingStatus.REVERSED }) {
                            return Violation(name, "REVERSED batch ${batch.id} has non-REVERSED items")
                        }
                    }
                    else -> return Violation(name, "clearing batch ${batch.id} in non-terminal ${batch.status}")
                }
            }
            return null
        }

        private fun checkSettled(
            batch: ClearingBatch,
            items: List<ClearingItem>,
            posting: ClearingSettlementPosting?,
        ): Violation? {
            if (items.size != batch.itemCount || items.any { it.status != ClearingStatus.SETTLED }) {
                return Violation(name, "settled batch ${batch.id}: ${items.size}/${batch.itemCount} items settled")
            }
            val sum = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }
            if (sum.compareTo(batch.totalDebit) != 0) {
                return Violation(
                    name,
                    "settled batch ${batch.id}: items sum $sum != totalDebit ${batch.totalDebit}",
                )
            }
            if (batch.totalCredit.compareTo(batch.totalDebit) != 0 || batch.netPosition.signum() != 0) {
                return Violation(name, "settled batch ${batch.id}: NET bilateral totals drifted")
            }
            if (posting == null || posting.reversed) {
                return Violation(name, "settled batch ${batch.id}: no unreversed net-settlement journal")
            }
            if (posting.amount.compareTo(batch.totalDebit) != 0) {
                return Violation(name, "settled batch ${batch.id}: journal ${posting.amount} != totalDebit")
            }
            return null
        }
    }

    /**
     * Money-path SEPA Direct Debit adoption: every mandate left the setup confirmation (a B2B
     * mandate parked in PENDING_CONFIRMATION never collected and never will — the debtor-bank
     * confirmation is part of setup), and every collection reached a terminal outcome. All
     * [com.openbank.simulation.model.SddCollectionStatus] values are terminal by construction;
     * the check exists so a future non-terminal status fails here, not in the money sweep.
     */
    val sddCompleteness = object : Invariant {
        private val terminal = SddCollectionStatus.entries.toSet()

        override val name = "sdd-completeness"
        override fun check(world: World): Violation? {
            world.sdd.mandates.forEach { mandate ->
                if (mandate.status == MandateStatus.PENDING_CONFIRMATION) {
                    return Violation(
                        name,
                        "sdd mandate ${mandate.id} parked in PENDING_CONFIRMATION: never confirmed by debtor bank",
                    )
                }
            }
            world.sdd.collections.forEach { collection ->
                if (collection.status !in terminal) {
                    return Violation(name, "sdd collection ${collection.id} stuck in ${collection.status}")
                }
            }
            return null
        }
    }

    /**
     * Money-path SEPA Direct Debit adoption: the collection-to-journal correspondence. A SETTLED
     * collection has exactly its debit journal (never reversed) and, when refunded, exactly its
     * refund journal; a REJECTED / REFUSED / FAILED collection moved no money (the fail-closed
     * authorisation and the pre-money fault mapping must never book a leg); a REVERSED
     * collection's debit journal was unwound; and a one-off (OOFF) mandate never settles more
     * than one collection — the money-side mirror of the policy's MD01 reuse reject. The
     * cross-entry drift itself is covered by conservationOfMoney.
     */
    val sddMoneyConservation = object : Invariant {
        override val name = "sdd-money-conservation"
        override fun check(world: World): Violation? {
            world.sdd.collections.forEach { collection ->
                when (collection.status) {
                    SddCollectionStatus.SETTLED -> {
                        if (collection.debitJournalId == null || collection.reversed) {
                            return Violation(name, "settled sdd collection ${collection.id} has no live debit journal")
                        }
                    }
                    SddCollectionStatus.REJECTED, SddCollectionStatus.REFUSED, SddCollectionStatus.FAILED -> {
                        if (collection.debitJournalId != null || collection.refundJournalId != null) {
                            return Violation(
                                name,
                                "${collection.status} sdd collection ${collection.id} booked a journal: " +
                                    "money moved for a non-settled collection",
                            )
                        }
                    }
                    SddCollectionStatus.REVERSED -> {
                        if (collection.debitJournalId == null || !collection.reversed) {
                            return Violation(
                                name,
                                "reversed sdd collection ${collection.id}: debit journal not unwound",
                            )
                        }
                        if (collection.refundJournalId != null) {
                            return Violation(name, "reversed sdd collection ${collection.id} carries a refund journal")
                        }
                    }
                }
                if (collection.refundJournalId != null && collection.status != SddCollectionStatus.SETTLED) {
                    return Violation(name, "refund journal on a ${collection.status} sdd collection ${collection.id}")
                }
            }
            world.sdd.mandates
                .filter { it.sequenceType == SequenceType.OOFF }
                .forEach { mandate ->
                    val settled = world.sdd.collectionsOf(mandate.id).count { it.status == SddCollectionStatus.SETTLED }
                    if (settled > 1) {
                        return Violation(name, "one-off sdd mandate ${mandate.id} settled $settled collections")
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
        sctInstCompleteness,
        clearingCompleteness,
        clearingBatchConservation,
        sddCompleteness,
        sddMoneyConservation,
        billingFeeConservation,
        interestCapitalizationConservation,
        statementCloseIntegrity,
    )

    /** A terminal-state guard used by the saga orchestrator. */
    fun isTerminal(state: SagaState): Boolean =
        state == SagaState.COMPLETED || state == SagaState.COMPENSATED || state == SagaState.FAILED
}
