// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.YearEndAllocationRecord
import java.time.Instant
import java.util.UUID

/** Attribution of year-end result allocations (ADR-0096 D3, V42). Rows are never overwritten. */
interface YearEndAllocationRepository {

    suspend fun findPending(fiscalYear: Int): YearEndAllocationRecord?

    suspend fun exists(idempotencyKey: String): Boolean

    /**
     * Insert a PENDING attribution in a transaction that first locks the year-close row
     * (`SELECT … FOR UPDATE`) and requires it to be DRAFT — so allocation and attestation, which
     * updates the same row, cannot interleave. Returns false when the year is not DRAFT or another
     * allocation is already PENDING (partial unique index).
     */
    suspend fun insertPending(fiscalYear: Int, idempotencyKey: String, allocatedBy: String, at: Instant): Boolean

    suspend fun markPosted(idempotencyKey: String, journalId: UUID, at: Instant)

    suspend fun markAbandoned(idempotencyKey: String, at: Instant)

    /** Every principal with a non-ABANDONED allocation for the year. */
    suspend fun allocators(fiscalYear: Int): Set<String>

    /**
     * Idempotency keys of posted `year-end-result:{year}:*` journals with no POSTED attribution for
     * that same journal — evidence of an unattributed closing journal, which attestation refuses.
     */
    suspend fun unattributedJournalKeys(fiscalYear: Int): List<String>
}
