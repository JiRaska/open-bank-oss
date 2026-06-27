// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.libs.persistence.outbox.OutboxMessage

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
    suspend fun saveAttested(record: YearCloseRecord, outbox: OutboxMessage): YearCloseRecord
}
