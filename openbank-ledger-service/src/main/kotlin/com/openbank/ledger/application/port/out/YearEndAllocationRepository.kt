// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import java.time.Instant
import java.util.UUID

/** Attribution of year-end result allocations (ADR-0096 D3, V42). Rows are never updated. */
interface YearEndAllocationRepository {

    /**
     * Runs [block] inside ONE database transaction that first takes `SELECT … FOR UPDATE` on the
     * fiscal year's `ledger_year_close` row; [block] receives whether that row is DRAFT. Every
     * repository call [block] makes — the journal posting included — joins this transaction, so the
     * closing journal and its attribution commit together or not at all, and attestation (which
     * updates the same row) cannot interleave.
     */
    suspend fun <T> inYearCloseLock(fiscalYear: Int, block: suspend (isDraft: Boolean) -> T): T

    /** Insert the attribution; must be called inside [inYearCloseLock]. */
    suspend fun record(fiscalYear: Int, idempotencyKey: String, allocatedBy: String, journalId: UUID, at: Instant)

    /** Every principal who allocated the year. */
    suspend fun allocators(fiscalYear: Int): Set<String>

    /**
     * Keys of `year-end-result:{year}:*` journals with no attribution for that same journal — which
     * the transactional write makes impossible, so a non-empty answer is tampering or a defect, and
     * attestation refuses it.
     */
    suspend fun unattributedJournalKeys(fiscalYear: Int): List<String>
}
