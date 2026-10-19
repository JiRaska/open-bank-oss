// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.cashflow

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CashFlowKind { PRINCIPAL, INTEREST, FEE, NOTIONAL_EXCHANGE, PREMIUM }

/** From the bank's side. */
enum class FlowDirection { IN, OUT }

enum class RateSource { FIXED, OBSERVED, PROJECTED, RATE_UNTIL_FIRST_FIXING, MISSING }

/**
 * ADR-0344 D4: `PROJECTED → FIXED → DUE → SETTLED → POSTED`, plus `CANCELLED`. Slice 2 reaches
 * PROJECTED and FIXED only (generation); the fixing, payment and posting slices drive the rest.
 */
enum class CashFlowState { PROJECTED, FIXED, DUE, SETTLED, POSTED, CANCELLED }

/** A flow's identity across regenerations: same leg, same period, same kind. */
data class FlowSlot(val legIndex: Int, val periodIndex: Int, val kind: CashFlowKind)

/**
 * One row of the stored cash-flow ledger. [legHash] is the [com.openbank.treasury.domain.model.LegSpec.specHash]
 * of the leg that produced it. When an approved override applies, [paymentDate]/[amount] are the
 * override's and [originalPaymentDate]/[originalAmount] keep what was generated.
 */
data class CashFlow(
    val flowId: UUID,
    val dealId: UUID,
    val scheduleVersion: Int,
    val legIndex: Int,
    val periodIndex: Int,
    val kind: CashFlowKind,
    val direction: FlowDirection,
    val currency: String,
    val accrualStart: LocalDate?,
    val accrualEnd: LocalDate?,
    val fixingDate: LocalDate?,
    val paymentDate: LocalDate,
    val notional: BigDecimal?,
    val rate: BigDecimal?,
    val rateSource: RateSource,
    val amount: BigDecimal?,
    val state: CashFlowState,
    val legHash: String,
    val manual: Boolean = false,
    val originalPaymentDate: LocalDate? = null,
    val originalAmount: BigDecimal? = null,
    val overrideId: UUID? = null,
    /** ADR-0344 D5: where an OBSERVED rate came from (IMPORT | MANUAL | FEED) and its provenance hash. */
    val fixingSource: String? = null,
    val fixingSha256: String? = null,
    val fixedAt: Instant? = null,
    /** ADR-0344 D6: the payment this flow is settled through, once one is proposed. */
    val paymentId: String? = null,
) {
    init {
        require(amount == null || amount.signum() >= 0) {
            "a flow amount is never negative; direction carries the sign"
        }
        require(state != CashFlowState.FIXED || amount != null) { "a FIXED flow has an amount" }
        require(manual == (overrideId != null)) { "a flow is manual exactly when an override applies" }
    }

    val slot: FlowSlot get() = FlowSlot(legIndex, periodIndex, kind)

    /** Apply an APPROVED override, keeping the generated values beside it. */
    fun withOverride(o: CashFlowOverride): CashFlow {
        check(o.status == OverrideStatus.APPROVED) { "only an APPROVED override applies" }
        return copy(
            paymentDate = o.paymentDate ?: paymentDate,
            amount = o.amount ?: amount,
            manual = true,
            originalPaymentDate = originalPaymentDate ?: paymentDate,
            originalAmount = if (manual) originalAmount else amount,
            overrideId = o.overrideId,
        )
    }
}

/** ADR-0344 D6: a flow already on a (non-cancelled) payment is never overridden: 409 FLOW_ALREADY_IN_PAYMENT. */
class FlowAlreadyInPaymentException(flowId: UUID) : IllegalStateException("flow $flowId is already on a payment")

enum class OverrideStatus { PENDING, APPROVED, REJECTED, CONFLICT }

/**
 * A four-eyes change to one flow's payment date and/or amount (ADR-0344 D4). Proposed by back
 * office or by an AI agent (a proposal only); approved or rejected by a DIFFERENT human. The slot
 * and [legHash] are those of the flow it was proposed against: they decide whether it survives a
 * regeneration.
 */
data class CashFlowOverride(
    val overrideId: UUID,
    val dealId: UUID,
    val flowId: UUID,
    val slot: FlowSlot,
    val legHash: String,
    val paymentDate: LocalDate?,
    val amount: BigDecimal?,
    val reason: String,
    val proposedBy: Actor,
    val proposedAt: Instant,
    val status: OverrideStatus = OverrideStatus.PENDING,
    val decidedBy: Actor? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
) {
    init {
        require(paymentDate != null || amount != null) { "an override changes the payment date, the amount, or both" }
        require(amount == null || (amount.signum() >= 0 && amount.scale() <= 2)) {
            "an override amount is non-negative with at most 2 decimal places"
        }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "an override needs a reason (1..$MAX_REASON)" }
    }

    fun approve(actor: Actor, at: Instant): CashFlowOverride {
        decide(actor, "approve")
        return copy(status = OverrideStatus.APPROVED, decidedBy = actor, decidedAt = at)
    }

    fun reject(actor: Actor, reason: String, at: Instant): CashFlowOverride {
        decide(actor, "reject")
        require(reason.isNotBlank()) { "a rejection needs a reason" }
        return copy(status = OverrideStatus.REJECTED, decidedBy = actor, decidedAt = at, decisionReason = reason)
    }

    /** Order matters as on a deal: an agent is refused as an agent even on its own proposal. */
    private fun decide(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a cash-flow override")
        }
        check(status == OverrideStatus.PENDING) { "cannot $action a $status override (expected PENDING)" }
        if (actor.id == proposedBy.id) {
            throw FourEyesViolationException("four-eyes: the proposer of an override must not $action it")
        }
    }

    companion object {
        private const val MAX_REASON = 1000

        /**
         * Only a PROJECTED or FIXED flow can be overridden in this slice: once a flow is DUE or beyond,
         * a payment or a journal may exist, and changing it is a correction (a later slice), not an override.
         */
        @Suppress("LongParameterList")
        fun propose(
            id: UUID,
            flow: CashFlow,
            paymentDate: LocalDate?,
            amount: BigDecimal?,
            reason: String,
            actor: Actor,
            at: Instant,
        ): CashFlowOverride {
            if (actor.type != ActorType.HUMAN && actor.type != ActorType.AI_AGENT) {
                throw ActorNotPermittedException("a ${actor.type} principal may not propose a cash-flow override")
            }
            if (flow.paymentId != null) throw FlowAlreadyInPaymentException(flow.flowId)
            check(flow.state == CashFlowState.PROJECTED || flow.state == CashFlowState.FIXED) {
                "a ${flow.state} flow cannot be overridden; it needs a correction"
            }
            require(amount == null || flow.amount != null || flow.state == CashFlowState.PROJECTED) {
                "this flow has no amount to override"
            }
            return CashFlowOverride(
                overrideId = id,
                dealId = flow.dealId,
                flowId = flow.flowId,
                slot = flow.slot,
                legHash = flow.legHash,
                paymentDate = paymentDate,
                amount = amount,
                reason = reason,
                proposedBy = actor,
                proposedAt = at,
            )
        }
    }
}

/** An approved override a regeneration could not carry over: its leg spec changed (ADR-0344 D4). */
data class CashFlowConflict(
    val conflictId: UUID,
    val dealId: UUID,
    val overrideId: UUID,
    val slot: FlowSlot,
    val fromScheduleVersion: Int,
    val toScheduleVersion: Int,
    val detectedAt: Instant,
    val reason: String,
)

/** What a regeneration produced: the new rows (overrides applied), the carried overrides, the conflicts. */
data class Regeneration(
    val flows: List<CashFlow>,
    val carried: List<CashFlowOverride>,
    val conflicts: List<CashFlowConflict>,
)

/**
 * Carry approved overrides onto a freshly generated schedule. An override survives when a flow
 * with the same slot exists AND the leg that produces it has the same spec hash; it then points at
 * the new flow. Otherwise it becomes a [CashFlowConflict] and its status CONFLICT — never dropped
 * silently, never re-applied to a flow whose terms moved underneath it.
 */
object ScheduleRegeneration {
    fun regenerate(
        generated: List<CashFlow>,
        approved: List<CashFlowOverride>,
        fromScheduleVersion: Int,
        newId: () -> UUID,
        at: Instant,
    ): Regeneration {
        val bySlot = generated.associateBy { it.slot }
        val carried = mutableListOf<CashFlowOverride>()
        val conflicts = mutableListOf<CashFlowConflict>()
        approved.filter { it.status == OverrideStatus.APPROVED }.forEach { o ->
            val target = bySlot[o.slot]
            when {
                target == null ->
                    conflicts +=
                        conflict(o, generated, fromScheduleVersion, newId, at, "the flow no longer exists")
                target.legHash != o.legHash ->
                    conflicts +=
                        conflict(
                            o,
                            generated,
                            fromScheduleVersion,
                            newId,
                            at,
                            "the leg spec that produced the flow changed",
                        )
                else -> carried += o.copy(flowId = target.flowId)
            }
        }
        val byFlow = carried.associateBy { it.flowId }
        val flows = generated.map { f -> byFlow[f.flowId]?.let(f::withOverride) ?: f }
        return Regeneration(flows, carried, conflicts)
    }

    @Suppress("LongParameterList")
    private fun conflict(
        o: CashFlowOverride,
        generated: List<CashFlow>,
        from: Int,
        newId: () -> UUID,
        at: Instant,
        why: String,
    ) = CashFlowConflict(
        conflictId = newId(),
        dealId = o.dealId,
        overrideId = o.overrideId,
        slot = o.slot,
        fromScheduleVersion = from,
        toScheduleVersion = generated.firstOrNull()?.scheduleVersion ?: (from + 1),
        detectedAt = at,
        reason = why,
    )
}
