// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.model.PortfolioSnapshot
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One stored version of a portfolio statement. Rows are never updated except to record that a
 * later correction superseded them ([supersededBy], [supersededAt]); the superseded version and
 * its holdings stay, so every figure ever served can be traced to the bytes it came from.
 */
data class StoredPortfolioStatement(
    val id: UUID,
    val idempotencyKey: String,
    val version: Int,
    /** The version this one corrected, or null for the first statement of the date. */
    val supersedes: UUID?,
    val supersededBy: UUID?,
    val supersededAt: Instant?,
    val sha256: String,
    val uploadedBy: String,
    val uploadedAt: Instant,
    val snapshot: PortfolioSnapshot,
)

/**
 * No CURRENT snapshot exists for the date. Mapped to 409, never to an empty list: an empty
 * portfolio and a missing statement must not look the same (ADR-0337 amendment D2).
 */
class PortfolioSnapshotMissingException(entity: String, date: LocalDate) :
    IllegalStateException("no portfolio statement of holdings is stored for $entity at $date")

/**
 * A unique constraint rejected the save — a concurrent upload of the same key, or a concurrent
 * correction of the same date, committed first. The caller re-reads and answers as its pre-check would.
 */
class DuplicatePortfolioStatementException(cause: Throwable) :
    RuntimeException("portfolio statement already stored", cause)

interface PortfolioStatementRepository {
    suspend fun findByIdempotencyKey(key: String): StoredPortfolioStatement?

    /** Atomically bind an accepted replay key; return the existing winner if another request bound it first. */
    suspend fun bindIdempotencyKey(key: String, statementId: UUID): StoredPortfolioStatement

    /** The current (not superseded) version for [entity] at [date], or null. */
    suspend fun current(entity: String, date: LocalDate): StoredPortfolioStatement?

    /** Every version for [entity] at [date], oldest first (the correction trail). */
    suspend fun versions(entity: String, date: LocalDate): List<StoredPortfolioStatement>

    /**
     * Inserts [stored] and its positions and, when [stored] supersedes a prior version, marks that
     * version superseded — in ONE transaction. @throws DuplicatePortfolioStatementException
     */
    suspend fun save(stored: StoredPortfolioStatement): StoredPortfolioStatement
}
