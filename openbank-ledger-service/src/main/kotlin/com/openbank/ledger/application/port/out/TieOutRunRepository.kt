// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.TieOutRunRecord
import java.time.LocalDate

/** Outbound persistence port for the audit trail of tie-out runs (ADR-0039 Phase B). */
interface TieOutRunRepository {

    suspend fun save(record: TieOutRunRecord): TieOutRunRecord

    suspend fun findLatest(): TieOutRunRecord?

    /**
     * The most recent run that checked [asOf] — the evidence
     * [com.openbank.ledger.infrastructure.schedule.AccountingDayScheduler] reads before advancing
     * that day `CUTOFF → TIED_OUT`. Latest by `runAt`, because a day can be re-checked (a BREAK
     * that was repaired and re-run): the newest verdict is the one that stands.
     */
    suspend fun findLatestFor(asOf: LocalDate): TieOutRunRecord?
}
