// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.statement.domain.model.BalanceAnchor
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementPeriod
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Pocket account identity read from account-service (single IBAN, per ADR-0024). */
data class PocketAccountInfo(
    val accountId: UUID,
    val iban: String,
    val holderName: String,
    val currencies: List<String>,
)

/** Reads the customer-facing booked entries (transaction-service, `status = COMPLETED`). */
interface BookedEntryPort {
    fun bookedEntries(accountId: UUID, currency: String, from: LocalDate, to: LocalDate): Uni<List<StatementEntry>>
}

/** Reads the independently-maintained per-pocket closing balance (balance-service) for reconciliation. */
interface BalancePort {
    fun closingBalance(accountId: UUID, currency: String, asOf: LocalDate): Uni<BalanceAnchor>
}

/** Reads pocket account identity / the set of currencies to close for an account. */
interface AccountInfoPort {
    fun pocketAccount(accountId: UUID): Uni<PocketAccountInfo>
}

/** Persists and queries the retained period-close records (the only stored artefact, ADR-0035 §F). */
interface StatementPeriodRepository {
    fun nextLegalSequence(accountId: UUID, currency: String): Uni<Long>
    fun save(period: StatementPeriod): Uni<StatementPeriod>

    /**
     * Atomically persist the closed [period] **and** its [event] in a single transaction, so a
     * crash or failure can never leave a CLOSED period whose `period.closed` event was never
     * emitted (the transactional-outbox invariant). Replaces the previous `save(...)` →
     * `outbox.append(...)` chain, which committed in two separate transactions (lost-event window).
     */
    fun saveWithOutbox(period: StatementPeriod, event: OutboxMessage): Uni<StatementPeriod>

    /**
     * The **standing** (non-SUPERSEDED) close for a window, or null. Superseded records stay in the
     * table for retention and remain reachable by [findBySequence]; they must never be returned here,
     * or a restated period would look closed-with-the-old-figures to the idempotency check and to
     * [priorClosing].
     */
    fun findByPeriod(accountId: UUID, currency: String, from: LocalDate, to: LocalDate): Uni<StatementPeriod?>

    /** Finds by legal sequence **including** superseded records — an issued statement page must stay
     *  reproducible for its full retention (ADR-0035 §D). */
    fun findBySequence(accountId: UUID, currency: String, legalSequence: Long): Uni<StatementPeriod?>

    /**
     * Atomically supersede [supersededId] and persist [replacement] plus its [event] in ONE
     * transaction (ADR-0035 §D). Either the whole restatement lands or none of it does — a
     * SUPERSEDED record with no replacement would leave the pocket with no standing close, and a
     * replacement without the status flip would violate the one-active-close-per-window index.
     */
    fun supersedeAndReplace(
        supersededId: UUID,
        replacement: StatementPeriod,
        event: OutboxMessage,
    ): Uni<StatementPeriod>
    fun priorClosing(accountId: UUID, currency: String, before: LocalDate): Uni<BigDecimal?>
    fun listForAccount(accountId: UUID): Uni<List<StatementPeriod>>

    /**
     * The `periodTo` of the most recently closed period for a pocket, or null if it has never been
     * closed. Drives the self-healing catch-up enumeration (ADR-0069 D3): the next run closes every
     * month after this date through the prior month.
     */
    fun latestClosedPeriodTo(accountId: UUID, currency: String): Uni<LocalDate?>
}

/**
 * Outbox repository for `account.statement.period.closed` events (ADR-0035 §F, ADR-0049 D3).
 * Extends libs [OutboxRepository] (listProcessable / markSent / markFailed) and adds the
 * reactive in-transaction append used by [StatementPeriodRepository.saveWithOutbox].
 */
interface StatementOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}

/**
 * Standalone outbox-append port (kept for backward compat with [StatementPeriodRepository.saveWithOutbox];
 * use [StatementOutboxRepository.persistInTransaction] for new code).
 *
 * @deprecated Prefer [StatementOutboxRepository.persistInTransaction].
 */
@Deprecated("Use StatementOutboxRepository.persistInTransaction")
interface StatementOutbox {
    fun append(message: OutboxMessage): Uni<Void>
}

/** @deprecated Use [com.openbank.libs.persistence.outbox.OutboxMessage] directly. */
@Deprecated(
    message = "Use com.openbank.libs.persistence.outbox.OutboxMessage instead",
    replaceWith = ReplaceWith("OutboxMessage", "com.openbank.libs.persistence.outbox.OutboxMessage"),
)
typealias StatementOutboxMessage = OutboxMessage
