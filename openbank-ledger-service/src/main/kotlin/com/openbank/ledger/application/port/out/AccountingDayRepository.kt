// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.LocalDate

/** Outbound persistence port for accounting-day state (ADR-0207 D2). */
interface AccountingDayRepository {

    suspend fun findByDate(businessDate: LocalDate): AccountingDayRecord?

    /** The most recently opened day that still accepts postings — the forward-correction target. */
    suspend fun findLatestOpen(): AccountingDayRecord?

    /**
     * The day with the highest business date regardless of status — the catch-up anchor for
     * [com.openbank.ledger.infrastructure.schedule.AccountingDayScheduler]: days after this one
     * have never been opened. Null only before the very first day is ever opened.
     */
    suspend fun findLatest(): AccountingDayRecord?

    /**
     * Every day currently in [status], ascending by business date. Bounded in practice by the
     * lifecycle itself: one row exists per calendar day and non-terminal statuses drain daily,
     * so a large result is itself the stuck-day signal, never a paging problem.
     */
    suspend fun findInStatus(status: AccountingDayStatus): List<AccountingDayRecord>

    /** Days in `[from, to]`, ascending by business date. */
    suspend fun findRange(from: LocalDate, to: LocalDate): List<AccountingDayRecord>

    /**
     * Insert a newly opened day AND its outbox row in the SAME database transaction
     * (transactional outbox, ADR-0003/0050). Fails on a duplicate business date — a day is
     * opened once.
     */
    suspend fun saveOpened(record: AccountingDayRecord, outbox: OutboxMessage): AccountingDayRecord

    /**
     * Persist a transition AND its [com.openbank.ledger.domain.event.AccountingDayTransitionedEvent]
     * outbox row in the SAME database transaction: either the day moves and the event is queued, or
     * neither happens. The update is conditional on the expected previous version, so two operators
     * racing the same transition cannot both win.
     */
    suspend fun saveTransition(
        record: AccountingDayRecord,
        expectedVersion: Long,
        outbox: OutboxMessage,
    ): AccountingDayRecord
}
