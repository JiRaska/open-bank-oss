// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.FlowSlot
import com.openbank.treasury.domain.reference.Ifrs9Category
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D7: PENDING (proposed, awaiting a second human) → APPROVED (the new deal version and its
 * ledger are committed; the delta journals are owed) → POSTED (every delta journal is in the ledger).
 * REJECTED is terminal. APPROVED is a real, durable state: the delta journals are posted AFTER the
 * version commits, idempotently, and an APPROVED correction is finished by the next day step.
 */
enum class CorrectionStatus { PENDING, APPROVED, POSTED, REJECTED }

/** A correction's base version moved (another correction or a term change landed first): 409. */
class CorrectionStaleException(message: String) : IllegalStateException(message)

/** A blocking payment (see `PaymentStatus.blocksDealChange`) covers a flow the correction changes: 409. */
class CorrectionBlockedByPaymentException(val dealId: UUID, val paymentId: UUID, val flowId: UUID) :
    IllegalStateException("payment $paymentId of deal $dealId covers changed flow $flowId and is still open")

/**
 * The partial terms a correction changes. Absent = unchanged. Legs are replaced as a whole (a leg
 * is one specification); the flat fields of a leg-described deal follow from the legs.
 */
data class CorrectionTerms(
    val legs: List<LegSpec>? = null,
    val rate: BigDecimal? = null,
    val calendars: List<String>? = null,
    val businessDayConvention: String? = null,
    val note: String? = null,
) {
    val isEmpty: Boolean get() = legs == null &&
        rate == null &&
        calendars == null &&
        businessDayConvention == null &&
        note == null
}

/** One journal a correction posts: the difference for one (event, flow-or-day) of the deal's history. */
data class DeltaJournal(
    /** The event whose amounts changed (SETTLEMENT, ACCRUAL, PAYMENT, …). */
    val sourceEvent: PostingEvent,
    /** The flow slot (`leg/period/kind`) or the ISO date the source event is keyed by. */
    val key: String,
    /** The date the source event belongs to. */
    val valueDate: LocalDate,
    val spec: JournalSpec,
)

/** Where a delta journal landed; [originalValueDate] is set when a closed day pushed it into today. */
data class PostedDelta(
    val idempotencyKey: String,
    val journalId: UUID,
    val entryDate: LocalDate,
    val originalValueDate: LocalDate?,
)

/** A flow of the base schedule and its counterpart in the corrected one (either may be absent). */
data class FlowChange(val slot: FlowSlot, val before: CashFlow?, val after: CashFlow?)

data class CorrectionPreview(
    val newFlows: List<CashFlow>,
    val changed: List<FlowChange>,
    val deltaJournals: List<DeltaJournal>,
)

/**
 * A four-eyes change to a BOOKED, CONFIRMED or SETTLED leg-described deal (ADR-0344 D7). Proposed by
 * a human or an AI agent (a proposal only); approved or rejected by a DIFFERENT human who is not any
 * term author of the deal — its creator, submitter, draft editors, this proposer and the proposers
 * of its earlier corrections.
 */
data class DealCorrection(
    val correctionId: UUID,
    val dealId: UUID,
    val baseVersion: Int,
    val baseScheduleVersion: Int,
    val terms: CorrectionTerms,
    val fullUnwind: Boolean,
    val reason: String,
    val effectiveFrom: LocalDate,
    val proposedBy: Actor,
    val proposedAt: Instant,
    val status: CorrectionStatus = CorrectionStatus.PENDING,
    val decidedBy: Actor? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
    val newVersion: Int? = null,
    val posted: List<PostedDelta> = emptyList(),
    /**
     * The proposer asked for a counterparty-limit breach to be overridden (the corrected exposure
     * exceeds the limit). Approval is refused until [limitOverride] — a senior's, who is no term
     * author — covers it; without the request a breach is refused at proposal.
     */
    val limitOverrideRequested: Boolean = false,
    val limitOverride: LimitOverride? = null,
) {
    init {
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a correction needs a reason (1..$MAX_REASON)" }
        require(fullUnwind || !terms.isEmpty) { "a correction changes at least one term, or fully unwinds the deal" }
        require(!fullUnwind || terms.isEmpty) { "a full unwind changes no terms" }
        require(posted.size <= MAX_DELTA_JOURNALS) { "at most $MAX_DELTA_JOURNALS delta journals" }
    }

    fun approve(actor: Actor, deal: Deal, priorAuthors: Set<String>, at: Instant): DealCorrection {
        decide(actor, deal, priorAuthors, "approve")
        if (limitOverride != null && actor.id == limitOverride.by.id) {
            throw FourEyesViolationException("four-eyes: the senior who overrode the limit must not also approve")
        }
        if (deal.version != baseVersion || deal.scheduleVersion != baseScheduleVersion) {
            throw CorrectionStaleException(
                "deal ${deal.id} is at version ${deal.version}/${deal.scheduleVersion}, the correction was " +
                    "proposed against $baseVersion/$baseScheduleVersion: propose it again",
            )
        }
        return copy(
            status = CorrectionStatus.APPROVED,
            decidedBy = actor,
            decidedAt = at,
            newVersion = deal.version + 1,
        )
    }

    fun reject(actor: Actor, deal: Deal, priorAuthors: Set<String>, reason: String, at: Instant): DealCorrection {
        decide(actor, deal, priorAuthors, "reject")
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a rejection needs a reason (1..$MAX_REASON)" }
        return copy(status = CorrectionStatus.REJECTED, decidedBy = actor, decidedAt = at, decisionReason = reason)
    }

    /**
     * A senior's override of the counterparty-limit breach the corrected terms cause (ADR-0315 D4
     * applied to a correction): bounded to the exposure measured now, by a human who is not a term
     * author. The approval re-measures and refuses if exposure has grown past it.
     */
    fun overrideLimit(
        actor: Actor,
        deal: Deal,
        priorAuthors: Set<String>,
        reason: String,
        check: LimitCheck,
        at: Instant,
    ): DealCorrection {
        decide(actor, deal, priorAuthors, "override the limit of")
        check(limitOverrideRequested) { "the correction did not ask for a limit override" }
        check(check.breached) { "the corrected terms breach no counterparty limit: nothing to override" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) {
            "a limit override needs a reason (1..$MAX_REASON)"
        }
        return copy(limitOverride = LimitOverride(actor, reason, at, check.exposureAfter, check.limit))
    }

    fun posted(deltas: List<PostedDelta>): DealCorrection {
        check(status == CorrectionStatus.APPROVED) { "only an APPROVED correction posts (is $status)" }
        return copy(status = CorrectionStatus.POSTED, posted = deltas)
    }

    /** Order matters: an agent is refused as an agent even on its own proposal. */
    private fun decide(actor: Actor, deal: Deal, priorAuthors: Set<String>, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a deal correction")
        }
        check(status == CorrectionStatus.PENDING) { "only a PENDING correction can be decided (is $status)" }
        val role = when (actor.id) {
            proposedBy.id -> "proposer"
            deal.createdBy.id -> "creator"
            deal.submittedBy?.id -> "submitter"
            in deal.termEditors -> "draft editor"
            in priorAuthors -> "author of an earlier correction"
            else -> null
        }
        role?.let { throw FourEyesViolationException("four-eyes: the correction's $it must not $action it") }
    }

    companion object {
        const val MAX_REASON = 500
        const val MAX_DELTA_JOURNALS = 2_000

        fun propose(
            id: UUID,
            deal: Deal,
            terms: CorrectionTerms,
            fullUnwind: Boolean,
            reason: String,
            effectiveFrom: LocalDate,
            actor: Actor,
            at: Instant,
            limitOverrideRequested: Boolean = false,
        ): DealCorrection {
            if (actor.type != ActorType.HUMAN && actor.type != ActorType.AI_AGENT) {
                throw ActorNotPermittedException("a ${actor.type} principal may not propose a deal correction")
            }
            Deal.requireCorrectable(deal)
            require(!effectiveFrom.isBefore(deal.tradeDate)) { "effectiveFrom must not precede the trade date" }
            return DealCorrection(
                id,
                deal.id,
                deal.version,
                deal.scheduleVersion,
                terms,
                fullUnwind,
                reason,
                effectiveFrom,
                actor,
                at,
                limitOverrideRequested = limitOverrideRequested,
            )
        }
    }
}

/**
 * The delta journals a correction implies (ADR-0344 D7): for every (event, flow-or-day) the deal has
 * already posted, the journal the NEW version implies minus the journal the OLD version implied,
 * netted line by line per (class, role, currency). Pure and deterministic: the posting-rule matrix
 * re-derives both from the stored ledgers, so "what was posted" is recomputed from the base schedule
 * exactly as the daily run posted it, never read back from the ledger and guessed at.
 *
 * Only events the deal has REACHED are re-derived (its settlement once settled, accruals through the
 * last accrued day, the flows paid by then); the future is left to the normal daily run on the new
 * ledger. A full unwind implies nothing, so its delta reverses everything posted.
 */
object CorrectionDelta {
    /** What the deal has posted so far, by matrix event. */
    data class Reached(val trade: Boolean, val settlement: Boolean, val lastAccrual: LocalDate?, val maturity: Boolean)

    @Suppress("LongParameterList")
    fun compute(
        correctionId: UUID,
        old: Deal,
        oldFlows: List<CashFlow>,
        new: Deal?,
        newFlows: List<CashFlow>,
        category: Ifrs9Category,
        reached: Reached,
    ): List<DeltaJournal> {
        val before = implied(old, oldFlows, category, reached)
        val after = new?.let { implied(it, newFlows, category, reached) } ?: emptyMap()
        val keys = (before.keys + after.keys).sortedWith(compareBy({ it.third }, { it.first.ordinal }, { it.second }))
        return keys.mapNotNull { k ->
            val net = LinkedHashMap<Triple<String, LedgerRole, String>, BigDecimal>()
            fun add(lines: List<PostingLine>, sign: Int) = lines.forEach { l ->
                val signed = if (l.side == Side.DEBIT) l.amount else l.amount.negate()
                net.merge(
                    Triple(l.accountingClass, l.role, l.currency),
                    signed.multiply(BigDecimal(sign)),
                    BigDecimal::add,
                )
            }
            add(after[k].orEmpty(), 1)
            add(before[k].orEmpty(), -1)
            val lines = net.filterValues { it.signum() != 0 }.map { (acct, v) ->
                PostingLine(
                    acct.first,
                    acct.second,
                    if (v.signum() >
                        0
                    ) {
                        Side.DEBIT
                    } else {
                        Side.CREDIT
                    },
                    v.abs(),
                    acct.third,
                )
            }
            lines.takeIf { it.size >= 2 }?.let {
                DeltaJournal(
                    k.first,
                    k.second,
                    k.third,
                    JournalSpec(
                        old.id,
                        PostingEvent.CORRECTION,
                        it,
                        suffix = "$correctionId:${k.first.key}:${k.second}",
                    ),
                )
            }
        }
    }

    /** (event, key, value date) → lines; payments keyed by the flow's slot so versions line up. */
    private fun implied(
        deal: Deal,
        flows: List<CashFlow>,
        category: Ifrs9Category,
        reached: Reached,
    ): Map<Triple<PostingEvent, String, LocalDate>, List<PostingLine>> {
        val out = LinkedHashMap<Triple<PostingEvent, String, LocalDate>, List<PostingLine>>()
        fun put(event: PostingEvent, key: String, date: LocalDate, spec: JournalSpec?) {
            spec?.let { out.merge(Triple(event, key, date), it.lines) { a, b -> a + b } }
        }
        if (reached.trade) put(PostingEvent.TRADE, "-", deal.tradeDate, MatrixPostings.trade(deal))
        if (reached.settlement) {
            put(PostingEvent.SETTLEMENT, "-", deal.valueDate, MatrixPostings.settlement(deal, flows, category))
        }
        reached.lastAccrual?.let { through -> runUntil(deal, flows, category, through, ::put) }
        if (reached.maturity) {
            val amortised = MatrixPostings.amortisedThrough(deal, flows, reached.lastAccrual ?: deal.valueDate)
            put(
                PostingEvent.MATURITY,
                "-",
                deal.maturityDate,
                MatrixPostings.maturity(deal, flows, category, amortised),
            )
        }
        return out
    }

    /** The daily run's accruals and payments through [through], each under its version-stable key. */
    private fun runUntil(
        deal: Deal,
        flows: List<CashFlow>,
        category: Ifrs9Category,
        through: LocalDate,
        put: (PostingEvent, String, LocalDate, JournalSpec?) -> Unit,
    ) {
        val bySlot = flows.associateBy { it.flowId }
        MatrixPostings.run(deal, flows, category, null, emptySet(), minOf(through, deal.maturityDate)).forEach { d ->
            val key = if (d.spec.event == PostingEvent.ACCRUAL) {
                d.entryDate.toString()
            } else {
                bySlot[UUID.fromString(d.spec.suffix)]?.slot?.let(::slotKey) ?: d.spec.suffix.orEmpty()
            }
            put(d.spec.event, key, d.entryDate, d.spec)
        }
    }

    fun slotKey(s: FlowSlot) = "${s.legIndex}/${s.periodIndex}/${s.kind}"

    /** Slots whose economics differ between the two schedules (or exist on one side only). */
    fun changes(before: List<CashFlow>, after: List<CashFlow>): List<FlowChange> {
        val b = before.associateBy { it.slot }
        val a = after.associateBy { it.slot }
        return (b.keys + a.keys).sortedWith(compareBy({ it.legIndex }, { it.periodIndex }, { it.kind }))
            .mapNotNull { s ->
                val x = b[s]
                val y = a[s]
                if (x != null && y != null && same(x, y)) null else FlowChange(s, x, y)
            }
    }

    private fun same(x: CashFlow, y: CashFlow) = x.direction == y.direction &&
        x.currency == y.currency &&
        x.paymentDate == y.paymentDate &&
        x.accrualStart == y.accrualStart &&
        x.accrualEnd == y.accrualEnd &&
        x.fixingDate == y.fixingDate &&
        eq(x.notional, y.notional) &&
        eq(x.rate, y.rate) &&
        eq(x.amount, y.amount)

    private fun eq(x: BigDecimal?, y: BigDecimal?) = if (x == null || y == null) x == y else x.compareTo(y) == 0
}
