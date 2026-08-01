// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.LocalDate

/** Outbound persistence port for statutory period closes (ADR-0096 D1). */
interface ClosedPeriodRepository {

    suspend fun findByPeriod(period: AccountingPeriod): ClosedPeriodRecord?

    /**
     * The narrowest FROZEN period containing [date], if any — the posting path's question.
     *
     * Narrowest on purpose: a month, its quarter and its year can all be frozen, and the operator
     * needs to be told which boundary actually refused the posting. Reporting the year when the
     * month is what sealed it sends them to the wrong remedy.
     */
    suspend fun findFrozenContaining(date: LocalDate): ClosedPeriodRecord?

    /** Closed-period records overlapping `[from, to]`, ascending by period start. */
    suspend fun findRange(from: LocalDate, to: LocalDate): List<ClosedPeriodRecord>

    /** Insert a new DRAFT, or refresh an existing DRAFT for the same period (upsert). */
    suspend fun saveDraft(record: ClosedPeriodRecord): ClosedPeriodRecord

    /**
     * Persist the DRAFT→FROZEN flip AND its `PeriodFrozen` outbox row in the SAME database
     * transaction (transactional outbox, ADR-0003/0050): either the period is sealed and the event
     * is queued, or neither happens. A frozen period that nobody was told about is exactly as bad
     * as an event for a period that did not freeze.
     */
    suspend fun saveFrozen(record: ClosedPeriodRecord, outbox: OutboxMessage): ClosedPeriodRecord
}
