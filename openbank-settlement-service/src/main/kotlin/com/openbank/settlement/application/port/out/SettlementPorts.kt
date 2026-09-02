// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import java.time.Instant
import java.util.UUID

interface SettlementRepository {
    suspend fun create(settlement: Settlement): Settlement
    suspend fun findById(id: UUID): Settlement?
    suspend fun updateStatus(id: UUID, status: SettlementStatus): Settlement

    /**
     * Atomically transition PENDING → DEBITED for [id], returning true only for the single caller
     * that wins. Lets the legacy (non-Temporal) settle path claim a settlement before moving money,
     * so concurrent same-key requests cannot both debit. (The Temporal path is guarded by the
     * workflow-id reuse policy instead.)
     */
    suspend fun claimForProcessing(id: UUID): Boolean

    /**
     * How many settlements are in [status] right now. Feeds the stranded-settlement gauges
     * (issue #5705) — see `SettlementStrandedGauge` for why age, not error rate, is the only
     * signal that can see a settlement whose saga stopped advancing.
     */
    suspend fun countByStatus(status: SettlementStatus): Long

    /**
     * `created_at` of the oldest settlement in [status], or `null` when none is in that state.
     * `null` must be reported as an age of zero, never as the last age seen: a state that
     * emptied and keeps publishing its old age is an alert that fires after the problem is
     * fixed, which is how an alert earns being ignored.
     */
    suspend fun oldestCreatedAt(status: SettlementStatus): Instant?
}

interface DebitPort {
    suspend fun debit(settlementId: UUID)
}

interface CreditPort {
    suspend fun credit(settlementId: UUID)
}

interface LedgerPort {
    suspend fun book(settlementId: UUID)
}
