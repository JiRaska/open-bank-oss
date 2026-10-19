// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.payment

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.reference.SettlementRail
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D6 + contract 9. RELEASED: released and handed to the institution-transfer rail through
 * the outbox (`treasury.payment.released.v1`) in the same transaction. SENT / SETTLED / FAILED follow
 * from the rail's status events. RAIL_NOT_SUPPORTED: released, but no rail can be determined from
 * the SSI (no BIC, or a rail that contradicts the currency), so nothing was handed to any rail; the
 * payment waits for a manual instruction outside the system. FAILED: the rail rejected it; its flows
 * stay linked and an independent human resolves it.
 */
enum class PaymentStatus {
    PROPOSED,
    RELEASED,
    SENT,
    SETTLED,
    FAILED,
    CANCELLED,
    RAIL_NOT_SUPPORTED,

    /** A released payment a second, independent human confirmed was settled outside the system. */
    MANUALLY_SETTLED,

    /** A released payment a second, independent human recorded as returned. Its flows stay linked. */
    RETURNED,
    ;

    /** Once released, a payment never frees its flows: only a terminal manual resolution follows. */
    val released: Boolean get() = this in setOf(RELEASED, SENT, RAIL_NOT_SUPPORTED)

    /** A status an independent human may resolve to MANUALLY_SETTLED or RETURNED. */
    val resolvable: Boolean get() = released || this == FAILED
}

/** The counterparty's effective settlement instruction as the payment was addressed from it. */
data class SsiSnapshot(
    val rail: SettlementRail,
    val beneficiaryAccount: String,
    val bic: String?,
    val ssiVersion: Int,
    val intermediaryBic: String? = null,
)

/** Contract 9.1: the rail a bank-own institution transfer travels on. */
enum class TransferRail { CERTIS, TARGET2, SWIFT }

/** What the rail reported about one released payment (contract 9.2). */
enum class RailStatus { ACCEPTED, SENT, SETTLED, REJECTED }

/** A rail status that cannot apply to the payment as it stands (never released, or contradicts a terminal state). */
class RailStatusConflictException(message: String) : IllegalStateException(message)

object TransferRails {
    /**
     * Contract 9.1: CZK -> CERTIS, EUR -> TARGET2, anything else -> SWIFT, taken from the SSI. The
     * SSI's own rail must agree with that (a SEPA instruction is the EUR one and travels TARGET2 as
     * a bank-own transfer); a contradicting rail, or an SSI without the creditor agent's BIC that an
     * institution transfer needs, determines NO rail — null, and the release ends RAIL_NOT_SUPPORTED.
     */
    fun of(currency: String, ssi: SsiSnapshot?): TransferRail? {
        if (ssi == null || ssi.bic.isNullOrBlank()) return null
        val expected = when (currency) {
            "CZK" -> TransferRail.CERTIS
            "EUR" -> TransferRail.TARGET2
            else -> TransferRail.SWIFT
        }
        val fromSsi = when (ssi.rail) {
            SettlementRail.CERTIS -> TransferRail.CERTIS
            SettlementRail.SEPA, SettlementRail.TARGET2 -> TransferRail.TARGET2
            SettlementRail.SWIFT -> TransferRail.SWIFT
        }
        return expected.takeIf { it == fromSsi }
    }
}

/**
 * One own-account payment: an outgoing instruction, or (IN) an expected receipt for nostro matching.
 * [nettedFlowIds] are the flows it settles; [termAuthors] everyone who created or changed the terms
 * of any of its deals — none of them may release it.
 */
@Suppress("TooManyFunctions") // one method per lifecycle transition (release, rail statuses, cancel, resolve, settle)
data class Payment(
    val paymentId: UUID,
    val status: PaymentStatus,
    val direction: FlowDirection,
    val counterpartyId: String,
    val currency: String,
    val amount: BigDecimal,
    val valueDate: LocalDate,
    val ssi: SsiSnapshot?,
    val nettedFlowIds: List<UUID>,
    val dealIds: List<UUID>,
    val termAuthors: Set<String>,
    val idempotencyKey: String,
    val createdAt: Instant,
    val railReference: String? = null,
    val releasedBy: String? = null,
    val releasedAt: Instant? = null,
    val statusReason: String? = null,
    /** Contract 9.1: the institution-transfer rail chosen at release; null before, or RAIL_NOT_SUPPORTED. */
    val transferRail: TransferRail? = null,
) {
    init {
        require(amount.signum() > 0 && amount.scale() <= 2) { "a payment amount is positive with at most 2 decimals" }
        require(nettedFlowIds.isNotEmpty() && nettedFlowIds.size <= MAX_FLOWS) {
            "a payment settles 1..$MAX_FLOWS flows"
        }
        require(direction == FlowDirection.IN || ssi != null) { "an outgoing payment is addressed from an SSI" }
        require(statusReason == null || statusReason.length <= MAX_REASON) {
            "a status reason has at most $MAX_REASON characters"
        }
    }

    /**
     * ADR-0344 D6: a HUMAN releases, never an AI agent or a machine, never the creator of a deal it
     * pays nor anyone who authored or changed its terms. The releaser role itself is checked by RBAC
     * and OPA before this.
     */
    fun release(actor: Actor, at: Instant, authors: Set<String> = termAuthors): Payment {
        val released = releasedBy(actor, at, authors)
        val rail = TransferRails.of(currency, ssi)
            ?: return released.railNotSupported(
                "RAIL_NOT_SUPPORTED: no institution-transfer rail follows from SSI ${ssi?.rail} in $currency " +
                    "(missing creditor BIC or a rail that contradicts the currency); instruct this payment manually",
            )
        return released.copy(transferRail = rail)
    }

    private fun releasedBy(actor: Actor, at: Instant, authors: Set<String>): Payment {
        if (actor.type !=
            ActorType.HUMAN
        ) {
            throw ActorNotPermittedException("a ${actor.type} principal may not release a payment")
        }
        check(status == PaymentStatus.PROPOSED) { "cannot release a $status payment (expected PROPOSED)" }
        check(direction == FlowDirection.OUT) { "an incoming payment is matched, never released" }
        if (actor.id in authors) {
            throw FourEyesViolationException(
                "four-eyes: the creator or a term author of a deal must not release its payment",
            )
        }
        return copy(status = PaymentStatus.RELEASED, releasedBy = actor.id, releasedAt = at)
    }

    /**
     * Contract 9.2, idempotent on (paymentId, status) and monotone: ACCEPTED records the rail
     * reference and stays RELEASED; SENT moves RELEASED -> SENT; SETTLED moves RELEASED/SENT ->
     * SETTLED; REJECTED moves RELEASED/SENT -> FAILED. A replay, or a status the payment is already
     * past (a late SENT after SETTLED), answers null — nothing to store. A status for a payment that
     * was never handed to the rail, or one contradicting a terminal outcome (SETTLED after FAILED),
     * is a [RailStatusConflictException]: never guessed at.
     */
    fun onRail(rail: RailStatus, reference: String?, reason: String?): Payment? {
        val ref = reference?.take(MAX_REFERENCE)
        return when (rail) {
            RailStatus.ACCEPTED -> accepted(ref)
            RailStatus.SENT -> sent(ref)
            RailStatus.SETTLED -> terminal(rail, PaymentStatus.SETTLED, SETTLED_ALREADY, ref, null)
            RailStatus.REJECTED ->
                terminal(rail, PaymentStatus.FAILED, FAILED_ALREADY, ref, (reason ?: "rejected by the rail"))
        }
    }

    private fun accepted(ref: String?): Payment? = when {
        status == PaymentStatus.RELEASED -> if (ref == null || ref == railReference) null else copy(railReference = ref)
        status in AFTER_RELEASE -> null
        else -> conflict(RailStatus.ACCEPTED)
    }

    private fun sent(ref: String?): Payment? = when {
        status == PaymentStatus.RELEASED -> copy(status = PaymentStatus.SENT, railReference = ref ?: railReference)
        status in AFTER_RELEASE -> null
        else -> conflict(RailStatus.SENT)
    }

    private fun terminal(
        rail: RailStatus,
        to: PaymentStatus,
        already: Set<PaymentStatus>,
        ref: String?,
        reason: String?,
    ): Payment? = when (status) {
        PaymentStatus.RELEASED, PaymentStatus.SENT -> copy(
            status = to,
            railReference = ref ?: railReference,
            statusReason = reason?.take(MAX_REASON) ?: statusReason,
        )
        in already -> null
        else -> conflict(rail)
    }

    private fun conflict(rail: RailStatus): Nothing =
        throw RailStatusConflictException("payment $paymentId is $status; a rail status $rail cannot apply")

    fun railNotSupported(reason: String): Payment {
        check(status == PaymentStatus.RELEASED) { "only a RELEASED payment is handed to a rail" }
        return copy(status = PaymentStatus.RAIL_NOT_SUPPORTED, statusReason = reason.take(MAX_REASON))
    }

    /** Only a PROPOSED payment is cancelled (its flows are freed); a released one is resolved instead. */
    fun cancel(actor: Actor, reason: String): Payment {
        if (actor.type !=
            ActorType.HUMAN
        ) {
            throw ActorNotPermittedException("a ${actor.type} principal may not cancel a payment")
        }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a cancellation needs a reason (1..$MAX_REASON)" }
        check(status == PaymentStatus.PROPOSED) {
            "cannot cancel a $status payment: a released payment is resolved, never cancelled"
        }
        return copy(status = PaymentStatus.CANCELLED, statusReason = reason)
    }

    /** A deal change (cancel, reverse, correction) withdraws its PROPOSED payments; nothing had left. */
    fun withdrawn(reason: String): Payment {
        check(status == PaymentStatus.PROPOSED) { "only a PROPOSED payment is withdrawn" }
        return copy(status = PaymentStatus.CANCELLED, statusReason = reason.take(MAX_REASON))
    }

    /**
     * A RELEASED / SENT / RAIL_NOT_SUPPORTED payment ends MANUALLY_SETTLED or RETURNED, recorded by a
     * second, independent human: never the releaser, never a term author ([authors], computed fresh).
     * Its flows are never freed.
     */
    fun resolve(actor: Actor, outcome: PaymentStatus, reason: String, authors: Set<String>): Payment {
        if (actor.type !=
            ActorType.HUMAN
        ) {
            throw ActorNotPermittedException("a ${actor.type} principal may not resolve a payment")
        }
        require(outcome == PaymentStatus.MANUALLY_SETTLED || outcome == PaymentStatus.RETURNED) {
            "a released payment resolves to MANUALLY_SETTLED or RETURNED"
        }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a resolution needs a reason (1..$MAX_REASON)" }
        check(status.resolvable) {
            "cannot resolve a $status payment (expected RELEASED, SENT, RAIL_NOT_SUPPORTED or FAILED)"
        }
        if (actor.id == releasedBy || actor.id in authors) {
            throw FourEyesViolationException("four-eyes: the releaser or a term author must not resolve a payment")
        }
        return copy(status = outcome, statusReason = reason)
    }

    /** A nostro statement entry matched this expected (or sent) payment. */
    fun settled(reference: String): Payment {
        check(status in SETTLEABLE) { "cannot settle a $status payment" }
        return copy(status = PaymentStatus.SETTLED, railReference = railReference ?: reference)
    }

    companion object {
        const val MAX_FLOWS = 500
        const val MAX_REASON = 500
        const val MAX_REFERENCE = 140

        private val SETTLED_ALREADY = setOf(PaymentStatus.SETTLED, PaymentStatus.MANUALLY_SETTLED)
        private val FAILED_ALREADY = setOf(PaymentStatus.FAILED, PaymentStatus.RETURNED)
        private val AFTER_RELEASE = setOf(
            PaymentStatus.SENT,
            PaymentStatus.SETTLED,
            PaymentStatus.FAILED,
            PaymentStatus.MANUALLY_SETTLED,
            PaymentStatus.RETURNED,
        )

        /**
         * Outgoing payments that have (or may have) left the funding account. Shared by the funding
         * check and pendingOut; one is still counted until the ledger holds the outflow journal of
         * every flow it pays.
         */
        val OUTFLOW_STATUSES: List<String> =
            listOf("RELEASED", "SENT", "RAIL_NOT_SUPPORTED", "MANUALLY_SETTLED", "SETTLED")

        private val SETTLEABLE = setOf(PaymentStatus.PROPOSED, PaymentStatus.SENT, PaymentStatus.RELEASED)

        fun key(flows: List<CashFlow>): String = if (flows.size == 1) {
            "treasury:${flows.single().dealId}:${flows.single().flowId}:payment"
        } else {
            val ids = flows.map { it.flowId.toString() }.sorted().joinToString(",")
            "treasury:netted:" + MessageDigest.getInstance("SHA-256").digest(ids.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** What a proposal run produced for one group of flows. */
sealed interface ProposalOutcome {
    data class Proposed(val payment: Payment) : ProposalOutcome

    /** The flows net to zero: nothing moves, nothing is proposed. */
    data class NettedToZero(val flowIds: List<UUID>) : ProposalOutcome

    /** No effective SSI for an outgoing amount: nothing is proposed; the reason is reported. */
    data class Unaddressable(val counterpartyId: String, val currency: String, val flowIds: List<UUID>) :
        ProposalOutcome
}

/**
 * ADR-0344 D6 proposal rule. Pure: FIXED flows with no payment, paid within the cut-off window,
 * grouped by (counterparty, currency, value date). A group under a netting agreement that covers its
 * currency with `paymentNetting = true` nets IN against OUT into ONE payment; otherwise every flow is
 * its own payment. OUT is addressed from the counterparty's effective SSI; IN is an expected receipt.
 */
object PaymentProposals {
    data class Candidate(val flow: CashFlow, val counterpartyId: String, val termAuthors: Set<String>)

    fun propose(
        candidates: List<Candidate>,
        netting: (counterpartyId: String, currency: String) -> Boolean,
        ssi: (counterpartyId: String, currency: String) -> SsiSnapshot?,
        newId: () -> UUID,
        at: Instant,
    ): List<ProposalOutcome> {
        val eligible = candidates.filter {
            it.flow.state == CashFlowState.FIXED &&
                it.flow.paymentId == null &&
                it.flow.amount != null
        }
        return eligible.groupBy { Triple(it.counterpartyId, it.flow.currency, it.flow.paymentDate) }
            .flatMap { (k, group) ->
                val (cp, ccy, date) = k
                val groups = if (netting(cp, ccy)) listOf(group) else group.map { listOf(it) }
                groups.map { g -> outcome(g, cp, ccy, date, ssi, newId, at) }
            }
    }

    @Suppress("LongParameterList")
    private fun outcome(
        g: List<Candidate>,
        cp: String,
        ccy: String,
        date: LocalDate,
        ssi: (String, String) -> SsiSnapshot?,
        newId: () -> UUID,
        at: Instant,
    ): ProposalOutcome {
        val net = g.sumOf { c ->
            checkNotNull(c.flow.amount).let {
                if (c.flow.direction ==
                    FlowDirection.IN
                ) {
                    it
                } else {
                    it.negate()
                }
            }
        }
        val ids = g.map { it.flow.flowId }
        if (net.signum() == 0) return ProposalOutcome.NettedToZero(ids)
        val direction = if (net.signum() > 0) FlowDirection.IN else FlowDirection.OUT
        val address = ssi(cp, ccy)
        if (direction == FlowDirection.OUT && address == null) return ProposalOutcome.Unaddressable(cp, ccy, ids)
        return ProposalOutcome.Proposed(
            Payment(
                paymentId = newId(),
                status = PaymentStatus.PROPOSED,
                direction = direction,
                counterpartyId = cp,
                currency = ccy,
                amount = net.abs(),
                valueDate = date,
                ssi = address,
                nettedFlowIds = ids,
                dealIds = g.map { it.flow.dealId }.distinct(),
                termAuthors = g.flatMap { it.termAuthors }.toSet(),
                idempotencyKey = Payment.key(g.map { it.flow }),
                createdAt = at,
            ),
        )
    }
}

/**
 * Funding check (#12115): an outgoing payment may not take the funding (nostro) account negative as
 * of its value date, net of the same-day outgoing treasury payments already released or sent.
 */
object FundingCheck {
    data class Result(val available: BigDecimal, val after: BigDecimal) {
        val exceeded: Boolean get() = after.signum() < 0
    }

    fun check(balanceAsOfValueDate: BigDecimal, pendingSameDayOut: BigDecimal, amount: BigDecimal): Result {
        val available = balanceAsOfValueDate - pendingSameDayOut
        return Result(available, available - amount)
    }
}

/** A deal cannot be cancelled, reversed or corrected while one of its payments is out of PROPOSED: 409. */
class DealHasReleasedPaymentException(dealId: UUID, paymentId: UUID) :
    IllegalStateException("deal $dealId has payment $paymentId released; resolve it before changing the deal")

/** The payment no longer matches its deals and flows (cancelled, corrected, re-scheduled, changed): 409. */
class PaymentStaleException(message: String) : IllegalStateException(message)

class FundingExceededException(val result: FundingCheck.Result, message: String) : RuntimeException(message)
