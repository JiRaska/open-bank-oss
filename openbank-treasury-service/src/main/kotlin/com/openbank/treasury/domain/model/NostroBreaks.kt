// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which side of a nostro reconciliation an unmatched item sits on (ADR-0315 D7). */
enum class BreakSide {
    /** A correspondent statement line the ledger has no matching journal line for. */
    STATEMENT,

    /** A ledger journal line on the nostro GL the correspondent's statement does not show. */
    LEDGER,
}

/** How a break was resolved: a later reconciliation [MATCHED] it, or a person closed it ([MANUAL]). */
enum class BreakResolutionKind {
    /** A later reconciliation no longer observed it — the item matched. */
    MATCHED,

    /** A senior treasury approver closed it with a recorded reason, after investigating it. */
    MANUAL,
}

/** One four-eyes step of a manual break resolution, as recorded in the append-only history. */
enum class ResolutionStep { PROPOSED, APPROVED, REJECTED }

/**
 * A reconciliation break (ADR-0315 D7): an item left unmatched by [NostroMatcher], persisted so it
 * carries an AGE. [firstSeenOn] is the day a reconciliation first observed it; [resolvedOn] the
 * day a later reconciliation no longer did (it matched). [ourSide] is stated from OUR side for
 * both kinds: a correspondent CRDT (money into our nostro) is our DEBIT.
 *
 * [breakKey] identifies the ITEM across repeated reconciliations: a statement line by its
 * statement and sequence, a ledger line by its line id — so observing the same reconciliation
 * twice opens nothing new.
 */
data class NostroBreak(
    val id: UUID,
    val breakKey: String,
    val iban: String,
    val glCode: String,
    val currency: String,
    val side: BreakSide,
    val ourSide: Side,
    val amount: BigDecimal,
    val bookingDate: LocalDate,
    val reference: String?,
    val statementUuid: UUID,
    val statementSequence: Int?,
    val ledgerLineId: UUID?,
    val firstSeenOn: LocalDate,
    val resolvedOn: LocalDate? = null,
    val alertedAt: Instant? = null,
    val resolutionKind: BreakResolutionKind? = if (resolvedOn == null) null else BreakResolutionKind.MATCHED,
    /** The proposed (and, once approved, the final) reason for a manual resolution. */
    val resolutionReason: String? = null,
    /** Who APPROVED a manual resolution — never the proposer. */
    val resolvedBy: String? = null,
    /** Who PROPOSED the manual resolution, and when; kept after approval as its first pair of eyes. */
    val proposedBy: String? = null,
    val proposedAt: Instant? = null,
    /** The stored row's version this copy was read at; every write is conditional on it. */
    val version: Long = 0,
) {
    init {
        require(amount.signum() > 0) { "a break amount is positive; its direction is ourSide" }
        require((side == BreakSide.STATEMENT) == (statementSequence != null)) {
            "a statement break names its statement sequence, and only a statement break"
        }
        require((side == BreakSide.LEDGER) == (ledgerLineId != null)) {
            "a ledger break names its ledger line, and only a ledger break"
        }
        require(resolvedOn == null || !resolvedOn.isBefore(firstSeenOn)) { "a break cannot resolve before it was seen" }
        require(resolutionKind == null || resolvedOn != null) { "only a resolved break has a resolution kind" }
        require((proposedBy == null) == (proposedAt == null) && (proposedBy == null) == (resolutionReason == null)) {
            "a resolution proposal names its proposer, time and reason together"
        }
        require((resolutionKind == BreakResolutionKind.MANUAL) == (resolvedBy != null)) {
            "a manual resolution, and only one, names its approver"
        }
        require(resolutionKind != BreakResolutionKind.MANUAL || (proposedBy != null && proposedBy != resolvedBy)) {
            "a manual resolution is approved by someone other than its proposer"
        }
    }

    val open: Boolean get() = resolvedOn == null

    val manuallyResolved: Boolean get() = resolutionKind == BreakResolutionKind.MANUAL

    /** An OPEN break with a resolution proposal awaiting a second person. */
    val resolutionProposed: Boolean get() = open && proposedBy != null

    /**
     * First pair of eyes: a person who has investigated an OPEN break proposes closing it with a
     * reason. The break stays open — it keeps ageing and alerting — until a DIFFERENT person approves.
     *
     * @throws IllegalArgumentException the reason is not [MIN_REASON_LENGTH]..[MAX_REASON_LENGTH] characters (trimmed)
     * @throws IllegalStateException the break is resolved, or already has a pending proposal (409)
     * @throws ActorNotPermittedException the actor is not a person
     */
    fun proposeResolution(reason: String, actor: Actor, at: Instant): NostroBreak {
        val text = normalizeReason(reason)
        requireHuman(actor)
        check(open) { "nostro break $id is already resolved" }
        check(proposedBy == null) { "nostro break $id already has a resolution proposal awaiting approval" }
        return copy(resolutionReason = text, proposedBy = actor.id, proposedAt = at)
    }

    /**
     * Second pair of eyes: closes the break as MANUAL. Never a posting — the item stays unmatched in
     * the reconciliation; only the break stops ageing and alerting.
     *
     * @throws FourEyesViolationException the approver is the proposer (422)
     * @throws IllegalStateException the break is resolved, has no pending proposal, or its proposal is
     *   not the one read at [proposalRead] (409)
     */
    fun approveResolution(actor: Actor, today: LocalDate, proposalRead: Instant): NostroBreak {
        requireHuman(actor)
        check(open) { "nostro break $id is already resolved" }
        val proposer = checkNotNull(proposedBy) { "nostro break $id has no resolution proposal to approve" }
        // The approver approves the proposal they READ (its time identifies its reason): one rejected
        // and re-proposed in between is a different proposal, and is a conflict, never approved blind.
        check(proposedAt == proposalRead) { "the resolution proposal of nostro break $id changed since it was read" }
        if (actor.id == proposer) {
            throw FourEyesViolationException("the proposer of a nostro break resolution may not approve it")
        }
        return copy(
            resolvedOn = maxOf(today, firstSeenOn),
            resolutionKind = BreakResolutionKind.MANUAL,
            resolvedBy = actor.id,
        )
    }

    /** Refuses a pending proposal with a reason (kept in the history); the break stays open, proposal cleared. */
    fun rejectResolution(reason: String, actor: Actor): NostroBreak {
        normalizeReason(reason)
        requireHuman(actor)
        check(open) { "nostro break $id is already resolved" }
        checkNotNull(proposedBy) { "nostro break $id has no resolution proposal to reject" }
        return clearProposal()
    }

    private fun requireHuman(actor: Actor) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("only a person may resolve a nostro break, not ${actor.type}")
        }
    }

    private fun clearProposal() = copy(resolutionReason = null, proposedBy = null, proposedAt = null)

    /** Open again: every resolution and proposal field cleared together, so the invariants still hold. */
    fun reopen(): NostroBreak = copy(resolvedOn = null, resolutionKind = null, resolvedBy = null).clearProposal()

    /** Resolved by a later MATCH; a pending manual proposal is moot and is cleared. */
    fun matched(today: LocalDate): NostroBreak =
        copy(resolvedOn = maxOf(today, firstSeenOn), resolutionKind = BreakResolutionKind.MATCHED).clearProposal()

    /** Business days from [firstSeenOn] to [today] (or to [resolvedOn] once resolved). */
    fun ageBusinessDays(today: LocalDate): Int = BusinessDays.between(firstSeenOn, resolvedOn ?: today)

    companion object {
        fun statementKey(statementUuid: UUID, sequence: Int) = "STMT:$statementUuid:$sequence"

        fun ledgerKey(lineId: UUID) = "LEDGER:$lineId"

        const val MIN_REASON_LENGTH = 10
        const val MAX_REASON_LENGTH = 500

        /** Trimmed, and long enough to say something an auditor can check, short enough to be one note. */
        fun normalizeReason(reason: String): String {
            val text = reason.trim()
            require(text.length in MIN_REASON_LENGTH..MAX_REASON_LENGTH) {
                "reason must be $MIN_REASON_LENGTH to $MAX_REASON_LENGTH characters, was ${text.length}"
            }
            return text
        }
    }
}

/**
 * Weekdays only. No holiday calendar: a TARGET2 / ČNB holiday counts as a business day, which can
 * only make a break read OLDER — an early alert, never a missed one.
 */
object BusinessDays {
    /** Number of Monday–Friday days d with [from] < d <= [to]; 0 when [to] is not after [from]. */
    fun between(from: LocalDate, to: LocalDate): Int {
        if (!to.isAfter(from)) return 0
        var days = 0
        var d = from.plusDays(1)
        while (!d.isAfter(to)) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days++
            d = d.plusDays(1)
        }
        return days
    }
}

/**
 * When an OPEN break is aged enough to alert: [ageDays] business days or more AND an amount of at
 * least [minAmount] in the break's own currency. `minAmount = 0` alerts on every aged break.
 */
data class BreakAlertPolicy(val ageDays: Int, val minAmount: BigDecimal) {
    init {
        require(ageDays >= 1) { "break-alert-age-days must be at least 1, was $ageDays" }
        require(minAmount.signum() >= 0) { "break-alert-min-amount must not be negative, was $minAmount" }
    }

    fun isAged(b: NostroBreak, today: LocalDate): Boolean =
        b.open && b.ageBusinessDays(today) >= ageDays && b.amount >= minAmount
}

/** What one observation changes: breaks to insert, and existing ones whose resolution changed. */
data class BreakChanges(
    val opened: List<NostroBreak>,
    val resolved: List<NostroBreak>,
    val reopened: List<NostroBreak>,
) {
    val isEmpty: Boolean get() = opened.isEmpty() && resolved.isEmpty() && reopened.isEmpty()
}

/**
 * Pure: turns one [NostroReconciliation] into [BreakChanges] against the breaks already stored.
 *
 * [existing] must contain every stored break this reconciliation can speak about — those of this
 * statement, and ledger breaks on the same GL booked inside the statement's period. An open break
 * in that scope that the reconciliation no longer lists is RESOLVED (it matched); one outside the
 * scope is left alone, since this reconciliation read no ledger lines for its day.
 *
 * A break a later reconciliation lists again is REOPENED when it had been resolved by a MATCH (the
 * match went away — e.g. the ledger line was reversed). A MANUALLY resolved break is NOT reopened by
 * being listed again: the item it describes is the same unmatched item the person investigated
 * (a statement line is immutable and is listed on every pass for 31 days), so re-observing it carries
 * no new information, and reopening on it would undo every manual resolution within the hour.
 */
object NostroBreakBook {

    fun observe(
        reconciliation: NostroReconciliation,
        statementUuid: UUID,
        existing: List<NostroBreak>,
        today: LocalDate,
        newId: () -> UUID,
    ): BreakChanges {
        val statement = reconciliation.statement
        val current = candidates(reconciliation, statementUuid, today, newId).associateBy { it.breakKey }
        val stored = existing.associateBy { it.breakKey }

        val opened = current.values.filter { it.breakKey !in stored }
        val reopened = stored.values
            .filter { !it.open && !it.manuallyResolved && it.breakKey in current }
            .map { it.reopen() }
        val resolved = stored.values
            .filter {
                it.open && it.breakKey !in current && inScope(it, reconciliation.glCode, statementUuid, statement)
            }
            .map { it.matched(today) }
        return BreakChanges(opened, resolved, reopened)
    }

    private fun inScope(b: NostroBreak, glCode: String, statementUuid: UUID, statement: NostroStatement): Boolean =
        when (b.side) {
            BreakSide.STATEMENT -> b.statementUuid == statementUuid
            BreakSide.LEDGER -> b.glCode == glCode && b.bookingDate in statement.firstDate..statement.lastDate
        }

    private fun candidates(
        r: NostroReconciliation,
        statementUuid: UUID,
        today: LocalDate,
        newId: () -> UUID,
    ): List<NostroBreak> {
        val s = r.statement
        val fromStatement = r.unmatchedStatementEntries.map { e ->
            NostroBreak(
                id = newId(),
                breakKey = NostroBreak.statementKey(statementUuid, e.sequence),
                iban = s.iban,
                glCode = r.glCode,
                currency = e.currency,
                side = BreakSide.STATEMENT,
                ourSide = if (e.direction == StatementDirection.CRDT) Side.DEBIT else Side.CREDIT,
                amount = e.amount,
                bookingDate = e.bookingDate,
                reference = e.reference,
                statementUuid = statementUuid,
                statementSequence = e.sequence,
                ledgerLineId = null,
                firstSeenOn = today,
            )
        }
        val fromLedger = r.unmatchedLedgerLines.map { l ->
            NostroBreak(
                id = newId(),
                breakKey = NostroBreak.ledgerKey(l.lineId),
                iban = s.iban,
                glCode = r.glCode,
                currency = l.currency,
                side = BreakSide.LEDGER,
                ourSide = l.side,
                amount = l.amount,
                bookingDate = l.reconciledDate,
                reference = l.description,
                statementUuid = statementUuid,
                statementSequence = null,
                ledgerLineId = l.lineId,
                firstSeenOn = today,
            )
        }
        return fromStatement + fromLedger
    }
}
