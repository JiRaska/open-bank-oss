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
 * ADR-0344 D6. RAIL_NOT_SUPPORTED: released, but the rail has no path for a bank-own (non-customer)
 * debit, so nothing was handed to it; the payment waits for a manual instruction outside the system.
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
}

/** The counterparty's effective settlement instruction as the payment was addressed from it. */
data class SsiSnapshot(val rail: SettlementRail, val beneficiaryAccount: String, val bic: String?, val ssiVersion: Int)

/**
 * One own-account payment: an outgoing instruction, or (IN) an expected receipt for nostro matching.
 * [nettedFlowIds] are the flows it settles; [termAuthors] everyone who created or changed the terms
 * of any of its deals — none of them may release it.
 */
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

    fun handedOff(reference: String): Payment {
        check(status == PaymentStatus.RELEASED) { "only a RELEASED payment is handed to a rail" }
        return copy(status = PaymentStatus.SENT, railReference = reference)
    }

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
        check(status.released) { "cannot resolve a $status payment (expected RELEASED, SENT or RAIL_NOT_SUPPORTED)" }
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

class FundingExceededException(val result: FundingCheck.Result, message: String) : RuntimeException(message)
