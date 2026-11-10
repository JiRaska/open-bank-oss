// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** Outbound persistence port for fiscal-year close records (ADR-0078 D5, increment 1). */
interface YearCloseRepository {

    suspend fun findByFiscalYear(fiscalYear: Int): YearCloseRecord?

    /**
     * True if [fiscalYear] has an ATTESTED year-close record — i.e. the period is locked and no
     * further journal activity (postings or reversals) may land in it. Backs the period-lock guard
     * in [com.openbank.ledger.application.usecase.LedgerService] (issue #869).
     */
    suspend fun isFiscalYearAttested(fiscalYear: Int): Boolean

    /** Insert a new DRAFT, or refresh an existing DRAFT for the same fiscal year (upsert). */
    suspend fun saveDraft(record: YearCloseRecord): YearCloseRecord

    /**
     * Persist the DRAFT→ATTESTED flip AND its [YearCloseAttestedEvent] outbox row in the SAME
     * database transaction (transactional outbox, ADR-0003/0050 — the invariant whose violation
     * in statement-service ADR-0078 explicitly calls out): either both commit or neither does.
     */
    /**
     * Record the result-allocation maker on the existing DRAFT (ADR-0096 D3). Returns null when
     * there is no DRAFT for the year (absent or already ATTESTED) — the caller refuses then.
     */
    suspend fun recordAllocation(
        fiscalYear: Int,
        allocatedBy: String,
        allocatedAt: Instant,
        journalId: UUID,
    ): YearCloseRecord?

    suspend fun saveAttested(record: YearCloseRecord, outbox: OutboxMessage): YearCloseRecord
}
