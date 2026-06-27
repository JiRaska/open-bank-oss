// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.`in`

import com.openbank.pid.domain.model.ApplicantSnapshot
import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationTrigger
import java.util.UUID

/**
 * Inbound port used by [com.openbank.pid.application.usecase.IdentityResolutionService] to turn an
 * ambiguous resolution into a durable four-eyes case, and to consult prior human decisions
 * (the adjudication cache) so a decided case deterministically steers the next /resolve.
 */
interface IdentityAdjudicationUseCase {
    /** A prior DECIDED case for this dedup key, or null. Consulted before opening a new case. */
    suspend fun priorDecision(dedupKey: String): PriorAdjudication?

    /** Open a case for the ambiguous applicant, or return the id of the existing active case (idempotent). */
    suspend fun openOrReuse(command: OpenCaseCommand): UUID
}

/** Inbound port for the operator cockpit (REST). */
interface ManageVerificationCaseUseCase {
    /** OPEN + AWAITING_SECOND_APPROVAL cases, newest-first. */
    suspend fun listActive(): List<VerificationCase>

    suspend fun get(id: UUID): VerificationCase?

    /** Record an approver's verdict. First vote → AWAITING; a distinct concurring vote → DECIDED (ADR-0030). */
    suspend fun decide(command: DecideCaseCommand): VerificationCase

    /** Withdraw an awaiting first proposal, returning the case to OPEN. */
    suspend fun reopen(command: ReopenCaseCommand): VerificationCase
}

data class OpenCaseCommand(
    val dedupKey: String,
    val trigger: VerificationTrigger,
    val applicant: ApplicantSnapshot,
    val blindIndex: String?,
    val candidatePartyIds: List<UUID>,
)

data class PriorAdjudication(val caseId: UUID, val verdict: CaseVerdict, val linkPartyId: UUID?)

data class DecideCaseCommand(
    val caseId: UUID,
    val approver: String,
    val verdict: CaseVerdict,
    val linkPartyId: UUID?,
    val notes: String?,
)

data class ReopenCaseCommand(val caseId: UUID, val actor: String)
