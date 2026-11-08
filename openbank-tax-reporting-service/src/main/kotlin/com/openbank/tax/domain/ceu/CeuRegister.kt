// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.ceu

import com.openbank.tax.domain.model.requireValid
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * The CEÚ account-register projection (ADR-0367 D7, issue #15292; spec
 * openbank-libs/governance/returns/ceu-daily.yaml).
 *
 * The register is kept as an append-only list of FACTS, each carrying two times:
 *
 *  - `effectiveOn` — valid time: the business date the change took effect (account opened,
 *    disposer authorised, ...).
 *  - `recordedAt`  — record time: when this service learnt it.
 *
 * A snapshot answers "what did the register look like on day D, as known at instant T". The daily
 * overview diffs the snapshot that was REPORTED last time (its own valid day, known at that
 * filing's assembly instant) against the snapshot as known NOW. A fact that arrives late — valid
 * on a day already reported — is therefore invisible to the earlier snapshot and visible to the
 * current one, so it surfaces as a change in the next overview instead of being lost. That is the
 * whole reason the projection is bitemporal rather than a mutable current-state table.
 *
 * Nothing here holds a name, birth number or address. The projection stores identifiers; the
 * person data CEÚ requires is read from party-service at render time, so this service keeps no
 * second copy of it (ADR-0118).
 */

/** A person's tie to an account that CEÚ reports. */
enum class CeuRole { HOLDER, DISPOSER, BENEFICIAL_OWNER }

/** One observed register change. Facts are never updated; a correction is another fact. */
sealed interface RegisterFact {
    /** The producing event's id (`ce-id`) — the idempotency key for redelivery. */
    val eventId: UUID
    val accountId: UUID
    val effectiveOn: LocalDate
    val recordedAt: Instant

    /** ADR-0252 taint of the producing event. A tainted fact never reaches a return. */
    val synthetic: Boolean

    data class AccountOpened(
        override val eventId: UUID,
        override val accountId: UUID,
        val accountNumber: String,
        val currency: String,
        override val effectiveOn: LocalDate,
        override val recordedAt: Instant,
        override val synthetic: Boolean,
    ) : RegisterFact {
        init {
            requireValid(accountNumber.isNotBlank()) { "account $accountId: account number must not be blank" }
        }
    }

    data class AccountClosed(
        override val eventId: UUID,
        override val accountId: UUID,
        override val effectiveOn: LocalDate,
        override val recordedAt: Instant,
        override val synthetic: Boolean,
    ) : RegisterFact

    /** A person becomes tied to an account. [relationId] is what a later end refers to. */
    data class RelationStarted(
        override val eventId: UUID,
        val relationId: UUID,
        override val accountId: UUID,
        val partyId: UUID,
        val role: CeuRole,
        override val effectiveOn: LocalDate,
        override val recordedAt: Instant,
        override val synthetic: Boolean,
    ) : RegisterFact

    /**
     * The tie ends. The producing event may not name the account (delegation lifecycle events
     * carry only the grant id), so the persistence adapter resolves [accountId] from the start.
     */
    data class RelationEnded(
        override val eventId: UUID,
        val relationId: UUID,
        override val accountId: UUID,
        override val effectiveOn: LocalDate,
        override val recordedAt: Instant,
        override val synthetic: Boolean,
    ) : RegisterFact
}

/** One person tie as it stands in a snapshot. */
data class RegisterPerson(val partyId: UUID, val role: CeuRole, val since: LocalDate)

/** One account as it stands in a snapshot. Equality is what the diff compares. */
data class RegisterAccount(
    val accountId: UUID,
    val accountNumber: String,
    val currency: String,
    val openedOn: LocalDate,
    val closedOn: LocalDate?,
    val persons: Set<RegisterPerson>,
)

/**
 * The register for one valid day as known at one instant, already stripped of synthetic data.
 * [droppedSyntheticAccounts] / [droppedSyntheticRelations] are the evidence ADR-0367 D4 requires:
 * the exclusion is counted, never assumed.
 */
data class RegisterSnapshot(
    val validOn: LocalDate,
    val knownAt: Instant,
    val accounts: Map<UUID, RegisterAccount>,
    val droppedSyntheticAccounts: Int,
    val droppedSyntheticRelations: Int,
) {
    companion object {
        fun empty(validOn: LocalDate, knownAt: Instant) = RegisterSnapshot(validOn, knownAt, emptyMap(), 0, 0)

        /**
         * Fold [facts] into the register for [validOn] as known at [knownAt].
         *
         * Synthetic exclusion happens HERE, before anything is diffed: a tainted account drops with
         * every tie to it, and a tainted tie drops from an otherwise real account. A fact the
         * fold cannot place (an end for a relation it never saw start, a tie to an account it
         * never saw open) is ignored rather than invented — the account is not in the register
         * until its opening is known.
         */
        fun fold(facts: Collection<RegisterFact>, validOn: LocalDate, knownAt: Instant): RegisterSnapshot {
            val visible = facts
                .filter { !it.recordedAt.isAfter(knownAt) && !it.effectiveOn.isAfter(validOn) }
                .sortedWith(compareBy<RegisterFact>({ it.effectiveOn }, { it.recordedAt }, { it.eventId }))

            val syntheticAccounts = visible
                .filter { it is RegisterFact.AccountOpened && it.synthetic }
                .map { it.accountId }
                .toSet()

            val opened = mutableMapOf<UUID, RegisterFact.AccountOpened>()
            val closedOn = mutableMapOf<UUID, LocalDate>()
            val relations = linkedMapOf<UUID, RegisterFact.RelationStarted>()
            var droppedRelations = 0

            visible.forEach { fact ->
                if (fact.accountId in syntheticAccounts) return@forEach
                when (fact) {
                    is RegisterFact.AccountOpened -> if (!fact.synthetic) opened.putIfAbsent(fact.accountId, fact)
                    is RegisterFact.AccountClosed -> closedOn[fact.accountId] = fact.effectiveOn
                    is RegisterFact.RelationStarted ->
                        if (fact.synthetic) droppedRelations++ else relations[fact.relationId] = fact
                    is RegisterFact.RelationEnded -> relations.remove(fact.relationId)
                }
            }

            val accounts = opened.values.associate { open ->
                val persons = relations.values
                    .filter { it.accountId == open.accountId }
                    .map { RegisterPerson(it.partyId, it.role, it.effectiveOn) }
                    .toSet()
                open.accountId to RegisterAccount(
                    accountId = open.accountId,
                    accountNumber = open.accountNumber,
                    currency = open.currency,
                    openedOn = open.effectiveOn,
                    closedOn = closedOn[open.accountId],
                    persons = persons,
                )
            }
            return RegisterSnapshot(validOn, knownAt, accounts, syntheticAccounts.size, droppedRelations)
        }
    }
}

/** What happened to one account between two snapshots. */
enum class ChangeKind { NEW, CHANGED, CLOSED }

/**
 * One record of the daily change overview. It carries the account's FULL current state, not a
 * field-level patch: a change record that only listed what moved could not be re-read on its own.
 */
data class RegisterChange(val kind: ChangeKind, val account: RegisterAccount)

/** The change overview for one reporting window. */
data class ChangeOverview(
    val window: ReportingWindow,
    val changes: List<RegisterChange>,
    /** Synthetic data excluded from the CURRENT snapshot, recorded on the filing. */
    val droppedSyntheticAccounts: Int,
    val droppedSyntheticRelations: Int,
    /** True when diffed against an empty register — the initial full load. */
    val initialLoad: Boolean,
) {
    companion object {
        /**
         * Diff [previous] (what was last reported) against [current]. An account that left the
         * register without a closing fact — e.g. its opening was later found to be synthetic — is
         * reported CLOSED on the window's last day rather than silently vanishing.
         */
        fun diff(window: ReportingWindow, previous: RegisterSnapshot, current: RegisterSnapshot): ChangeOverview {
            val changes = mutableListOf<RegisterChange>()
            current.accounts.values.forEach { now ->
                val before = previous.accounts[now.accountId]
                when {
                    before == null -> changes += RegisterChange(
                        if (now.closedOn != null && now.closedOn <= window.to) ChangeKind.CLOSED else ChangeKind.NEW,
                        now,
                    )
                    before == now -> Unit
                    before.closedOn == null && now.closedOn != null -> changes += RegisterChange(ChangeKind.CLOSED, now)
                    else -> changes += RegisterChange(ChangeKind.CHANGED, now)
                }
            }
            previous.accounts.values
                .filter { it.accountId !in current.accounts && it.closedOn == null }
                .forEach { changes += RegisterChange(ChangeKind.CLOSED, it.copy(closedOn = window.to)) }
            return ChangeOverview(
                window = window,
                changes = changes.sortedBy { it.account.accountNumber },
                droppedSyntheticAccounts = current.droppedSyntheticAccounts,
                droppedSyntheticRelations = current.droppedSyntheticRelations,
                initialLoad = previous.accounts.isEmpty(),
            )
        }
    }
}

/**
 * The calendar days one working-day overview covers, inclusive. Submitted on [reportingDay];
 * covers every day since the previous working day's window ended, so Friday-to-Sunday changes
 * land in Monday's overview (ČNB FAQ for CEÚ institutions).
 */
data class ReportingWindow(val reportingDay: LocalDate, val from: LocalDate, val to: LocalDate) {
    init {
        requireValid(!from.isAfter(to)) { "window $from..$to is empty" }
        requireValid(to.isBefore(reportingDay)) { "window must end before its reporting day $reportingDay" }
    }

    companion object {
        /** [previousWorkingDay] is the calendar's working day strictly before [reportingDay]. */
        fun forReportingDay(reportingDay: LocalDate, previousWorkingDay: LocalDate): ReportingWindow =
            ReportingWindow(reportingDay, previousWorkingDay, reportingDay.minusDays(1))
    }
}
