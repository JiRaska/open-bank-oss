// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.CloseCompleteness
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
import java.time.LocalDate

/**
 * A participant's report. [callerPrincipal] is the VERIFIED service-account name of the caller
 * (`ServiceAccountIdentity.verifiedPrincipalName`), or null for a token that is not one; the use
 * case compares it with the participant's declared principal (ADR-0342 D2).
 */
data class ReportParticipationCommand(
    val businessDate: LocalDate,
    val participant: String,
    val outcome: ParticipationOutcome,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val callerPrincipal: String?,
)

/** An operator waiving a missing participant for one day (ADR-0342 D3). */
data class WaiveParticipationCommand(
    val businessDate: LocalDate,
    val participant: String,
    val reason: String?,
    val waivedBy: String,
)

interface CloseParticipationUseCase {

    /** The declared participant names, from configuration. */
    fun declaredParticipants(): Set<String>

    suspend fun report(command: ReportParticipationCommand): CloseParticipationRecord

    suspend fun waive(command: WaiveParticipationCommand): CloseParticipationRecord

    suspend fun completeness(businessDate: LocalDate): CloseCompleteness
}
