// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.monitoring.MonitoringEvaluationRecord
import com.openbank.aml.domain.monitoring.MonitoringHit
import com.openbank.aml.domain.monitoring.PackIdentity
import java.time.Instant
import java.util.UUID

/** Durable record of what the scheduled pack evaluation established (ADR-0371 slice 2). */
interface MonitoringEvaluationRepository {
    /** Parties with a counter bucket starting at or after [since], after [afterPartyId], in id order. */
    suspend fun activeParties(since: Instant, afterPartyId: UUID?, limit: Int): List<UUID>

    /** Stores the latest evaluation of the party under this pack content. */
    suspend fun recordEvaluation(record: MonitoringEvaluationRecord)

    /**
     * Records a hit once per party, scenario and window; a repeat only refreshes its evidence.
     * Returns the stored hit and whether this call created it.
     */
    suspend fun recordHit(hit: MonitoringHit, pack: PackIdentity, snapshotJson: String, at: Instant): RecordedHit

    /** Links the hit to the case opened for it. A hit that already has a case is left alone. */
    suspend fun markCaseOpened(hitId: UUID, caseId: UUID)

    /** Creation instant of the oldest pack-monitoring case nobody has decided yet (rail-opened cases excluded). */
    suspend fun oldestUndecidedMonitoringCase(): Instant?
}

data class RecordedHit(val hitId: UUID, val created: Boolean, val caseId: UUID?)
