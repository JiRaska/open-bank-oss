// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.model.LedgerNostroLine
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

interface NostroStatementRepository {
    suspend fun findById(id: UUID): StoredStatement?

    suspend fun findByIdempotencyKey(key: String): StoredStatement?

    suspend fun findByAccountAndStatementId(iban: String, statementId: String): StoredStatement?

    /** Statement row and all its entries in ONE transaction. @throws DuplicateStatementException */
    suspend fun save(stored: StoredStatement): StoredStatement
}

/**
 * ledger-service's READ surface (GET /api/v1/journals, GET /api/v1/journals/trial-balance), on
 * treasury's own identity. Never writes: reconciliation lists differences, it does not post them.
 */
interface LedgerReadPort {
    /** Booked (POSTED or REVERSED) real journal lines on [glCode] with entry date in [from]..[to]. */
    suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine>

    /**
     * Net (debit − credit) of [glCode] over every real booked journal with entry date <= [asOf],
     * in the ledger's BASE currency (CZK) — the trial balance aggregates `base_amount` only, so this
     * is meaningless for a nostro held in any other currency and must not be asked for one.
     */
    suspend fun glBalance(glCode: String, asOf: LocalDate): BigDecimal
}
