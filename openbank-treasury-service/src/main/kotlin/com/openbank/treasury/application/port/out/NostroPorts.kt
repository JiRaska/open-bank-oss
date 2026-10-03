// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.NostroStatement
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Raised when a reconciliation names a statement that was never uploaded. Mapped to 404. */
class StatementNotFoundException(id: UUID) : NoSuchElementException("nostro statement $id not found")

/** An uploaded correspondent statement, with who supplied it and a digest of the exact bytes. */
data class StoredStatement(
    val id: UUID,
    val idempotencyKey: String,
    val glCode: String,
    val sha256: String,
    val uploadedBy: String,
    val uploadedAt: Instant,
    val statement: NostroStatement,
)

/**
 * Raised by [NostroStatementRepository.save] when a unique constraint (idempotency key, or the
 * account + statement id pair) rejected the insert — a concurrent upload won the race after this
 * one's pre-check. The caller re-reads and answers exactly as the pre-check would have.
 */
class DuplicateStatementException(cause: Throwable) : RuntimeException("nostro statement already stored", cause)

/**
 * The ledger answered in a way that is not a balance and not its "I do not hold that account" —
 * e.g. a 404 from a ledger that does not serve the route yet. Mapped to 502: the reconciliation
 * cannot be computed, and saying "not stated" would be a false reason.
 */
class LedgerUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface NostroStatementRepository {
    suspend fun findById(id: UUID): StoredStatement?

    suspend fun findByIdempotencyKey(key: String): StoredStatement?

    suspend fun findByAccountAndStatementId(iban: String, statementId: String): StoredStatement?

    /** Statement row and all its entries in ONE transaction. @throws DuplicateStatementException */
    suspend fun save(stored: StoredStatement): StoredStatement

    /** Ids of statements whose closing date is on or after [since], oldest first (the break sweep's scope). */
    suspend fun statementIdsSince(since: LocalDate): List<UUID>
}

/** Raised when a break listing names an IBAN that is not a configured nostro account. Mapped to 404. */
class NostroAccountNotFoundException(iban: String) : NoSuchElementException("nostro account $iban is not configured")

/**
 * Persisted reconciliation breaks (ADR-0315 D7). The break key is unique, so a concurrent
 * observation of the same reconciliation cannot open an item twice.
 */
interface NostroBreakRepository {
    /**
     * Every stored break (open or resolved) on [glCode] that a reconciliation of [statementUuid]
     * over [from]..[to] can speak about: that statement's own breaks, and ledger breaks booked in
     * the period.
     */
    suspend fun inScope(glCode: String, statementUuid: UUID, from: LocalDate, to: LocalDate): List<NostroBreak>

    /** Inserts the opened breaks and updates the resolution of the others, in ONE transaction. */
    suspend fun apply(changes: BreakChanges)

    suspend fun byIban(iban: String, includeResolved: Boolean): List<NostroBreak>

    suspend fun open(): List<NostroBreak>

    /**
     * Stamps [alertedAt] on the break if it has none yet and, in the SAME transaction, writes the
     * outbox event. false when another pass already alerted it — then nothing is written.
     */
    suspend fun markAlerted(breakId: UUID, alertedAt: Instant, eventType: String, payload: String): Boolean
}

/**
 * ledger-service's READ surface (GET /api/v1/journals, GET /api/v1/journals/accounts/{code}/balance),
 * on treasury's own identity. Never writes: reconciliation lists differences, it does not post them.
 */
interface LedgerReadPort {
    /** Booked (POSTED or REVERSED) real journal lines on [glCode] with entry date in [from]..[to]. */
    suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine>

    /**
     * Net (debit − credit) of [glCode] in its NATIVE [currency] — the sum of the lines' own
     * `amount`, never the CZK `base_amount` — over every real booked journal with entry date
     * <= [asOf] (#11107). One code path for every nostro, CZK included.
     *
     * NULL only when the ledger does not know [glCode] at all (its unknown-account 404): the one
     * case in which no balance can be stated. Any other 404 (a route the ledger does not serve) is a
     * [LedgerUnavailableException]; any other failure propagates — a figure is never guessed.
     */
    suspend fun accountBalance(glCode: String, currency: String, asOf: LocalDate): BigDecimal?
}
