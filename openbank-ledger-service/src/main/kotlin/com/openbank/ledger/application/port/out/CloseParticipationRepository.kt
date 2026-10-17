// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.CloseParticipationRecord
import java.time.LocalDate

/** Outbound persistence port for day-close participation reports (ADR-0342 D1). */
interface CloseParticipationRepository {

    /** Append one report. History is never rewritten; the latest row per participant wins on read. */
    suspend fun append(record: CloseParticipationRecord): CloseParticipationRecord

    /** Every report for [businessDate], oldest first. */
    suspend fun findForDate(businessDate: LocalDate): List<CloseParticipationRecord>
}
