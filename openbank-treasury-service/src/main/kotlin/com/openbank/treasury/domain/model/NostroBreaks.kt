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
    }

    val open: Boolean get() = resolvedOn == null

    /** Business days from [firstSeenOn] to [today] (or to [resolvedOn] once resolved). */
    fun ageBusinessDays(today: LocalDate): Int = BusinessDays.between(firstSeenOn, resolvedOn ?: today)

    companion object {
        fun statementKey(statementUuid: UUID, sequence: Int) = "STMT:$statementUuid:$sequence"

        fun ledgerKey(lineId: UUID) = "LEDGER:$lineId"
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
        val reopened = stored.values.filter { !it.open && it.breakKey in current }.map { it.copy(resolvedOn = null) }
        val resolved = stored.values
            .filter {
                it.open && it.breakKey !in current && inScope(it, reconciliation.glCode, statementUuid, statement)
            }
            .map { it.copy(resolvedOn = maxOf(today, it.firstSeenOn)) }
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
                bookingDate = l.entryDate,
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
