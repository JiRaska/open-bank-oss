// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest.dto

import com.openbank.aml.domain.report.DispositionProposal
import com.openbank.aml.domain.report.OutcomeLabel
import com.openbank.aml.domain.report.SuspiciousTransactionReport
import java.time.Instant
import java.util.UUID

data class OutcomeResponse(val vocabularyId: String, val vocabularyRevision: Int, val vocabularyHash: String, val code: String)

fun OutcomeLabel.toResponse() = OutcomeResponse(vocabularyId, vocabularyRevision, vocabularyHash, code)

data class ProposeDispositionRequest(val disposition: String?, val outcomeCode: String?, val note: String?)

data class DecideDispositionRequest(val approve: Boolean?, val reason: String?)

data class DispositionProposalResponse(
    val proposalId: String,
    val caseId: UUID,
    val disposition: String,
    val outcome: OutcomeResponse,
    val note: String?,
    val state: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val executedAt: Instant?,
)

fun DispositionProposal.toResponse() = DispositionProposalResponse(
    proposalId = id,
    caseId = action.caseId,
    disposition = action.disposition.name,
    outcome = action.outcome.toResponse(),
    note = action.note,
    state = state.name,
    proposedBy = proposedBy,
    proposedAt = proposedAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    decisionReason = decisionReason,
    executedAt = executedAt,
)

data class ReportedMovementDto(val journalEntryId: UUID?, val accountId: UUID?, val currency: String?)

data class DraftReportRequest(
    val partyId: UUID?,
    val narrative: String?,
    val caseIds: List<UUID>?,
    val movements: List<ReportedMovementDto>?,
)

data class RecordFilingRequest(val filingReference: String?, val filedAt: Instant?, val outcomeCode: String?)

data class WithdrawReportRequest(val outcomeCode: String?)

data class ReportResponse(
    val reportId: UUID,
    val partyId: UUID,
    val state: String,
    val narrative: String,
    val caseIds: List<UUID>,
    val movements: List<ReportedMovementDto>,
    val draftedBy: String,
    val draftedAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val filingReference: String?,
    val filedAt: Instant?,
    val filingRecordedBy: String?,
    val filingRecordedAt: Instant?,
    val withdrawnBy: String?,
    val withdrawnAt: Instant?,
    val outcome: OutcomeResponse?,
    val updatedAt: Instant,
)

fun SuspiciousTransactionReport.toResponse() = ReportResponse(
    reportId = id,
    partyId = partyId,
    state = state.name,
    narrative = narrative,
    caseIds = caseIds,
    movements = movements.map { ReportedMovementDto(it.journalEntryId, it.accountId, it.currency) },
    draftedBy = draftedBy,
    draftedAt = draftedAt,
    approvedBy = approvedBy,
    approvedAt = approvedAt,
    filingReference = filingReference,
    filedAt = filedAt,
    filingRecordedBy = filingRecordedBy,
    filingRecordedAt = filingRecordedAt,
    withdrawnBy = withdrawnBy,
    withdrawnAt = withdrawnAt,
    outcome = outcome?.toResponse(),
    updatedAt = updatedAt,
)
