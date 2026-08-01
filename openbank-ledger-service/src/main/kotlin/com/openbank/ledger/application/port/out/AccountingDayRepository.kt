// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.LocalDate

/** Outbound persistence port for accounting-day state (ADR-0207 D2). */
interface AccountingDayRepository {

    suspend fun findByDate(businessDate: LocalDate): AccountingDayRecord?

    /** The most recently opened day that still accepts postings — the forward-correction target. */
    suspend fun findLatestOpen(): AccountingDayRecord?

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
