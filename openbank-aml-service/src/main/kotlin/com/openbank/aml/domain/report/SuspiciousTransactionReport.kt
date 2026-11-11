// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.report

import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import java.time.Instant
import java.util.UUID

enum class ReportState { DRAFT, APPROVED, FILED, WITHDRAWN }

/** A booked movement a report rests on, by the booking's own key (see `aml_booked_movement`). */
data class ReportedMovement(val journalEntryId: UUID, val accountId: UUID, val currency: String)

/** A structured outcome: a code of one pinned revision of the outcome vocabulary (ADR-0371 §10). */
data class OutcomeLabel(val vocabularyId: String, val vocabularyRevision: Int, val vocabularyHash: String, val code: String)

/** Raised when a report transition is not allowed from its current state. */
class ReportStateViolation(message: String) : IllegalStateException(message)

/**
 * The suspicious-transaction report (ADR-0371 §6): an aggregate with its own state and trail, not
 * a view computed from cases.
 *
 * ```
 *   DRAFT --approve(approver != drafter)--> APPROVED --file(reference, instant)--> FILED
 *     |                                        |
 *     +------------- withdraw(outcome) --------+--> WITHDRAWN
 * ```
 *
 * Four-eyes is the fleet maker-checker primitive ([Proposal.approve]), so the rule that the
 * approver differs from the drafter is the same code every other sensitive action uses.
 *
 * FILED records that a named officer filed the report OUTSIDE this system and under which
 * reference. Nothing here submits anything anywhere, and nothing here may say that it did.
 */
data class SuspiciousTransactionReport(
    val id: UUID,
    val partyId: UUID,
    val state: ReportState,
    val narrative: String,
    val caseIds: List<UUID>,
    val movements: List<ReportedMovement>,
    val draftedBy: String,
    val draftedAt: Instant,
    val approvedBy: String? = null,
    val approvedAt: Instant? = null,
    val filingReference: String? = null,
    val filedAt: Instant? = null,
    val filingRecordedBy: String? = null,
    val filingRecordedAt: Instant? = null,
    val withdrawnBy: String? = null,
    val withdrawnAt: Instant? = null,
    /** Set when the report closes: on filing and on withdrawal. */
    val outcome: OutcomeLabel? = null,
    val updatedAt: Instant = draftedAt,
) {
    init {
        require(draftedBy.isNotBlank()) { "a report has a drafter" }
        require(narrative.isNotBlank() && narrative.length <= MAX_NARRATIVE) { "narrative is required and bounded" }
        require(caseIds.isNotEmpty() && caseIds.size <= MAX_LINKS) { "a report rests on at least one case" }
        require(caseIds.distinct().size == caseIds.size) { "a case is linked once" }
        require(movements.size <= MAX_LINKS && movements.distinct().size == movements.size) { "movements are linked once" }
    }

    fun approve(approver: String, at: Instant): SuspiciousTransactionReport {
        requireState(ReportState.DRAFT)
        // Throws MakerCheckerViolation when the approver is the drafter.
        Proposal(id.toString(), id, draftedBy, draftedAt).approve(approver, at)
        return copy(state = ReportState.APPROVED, approvedBy = approver, approvedAt = at, updatedAt = at)
    }

    /**
     * Records a filing performed outside the system. [filedAt] is when the officer filed it and may
     * not be in the future or before the approval; [recordedAt] is when it was written down here.
     */
    fun recordFiling(
        reference: String,
        filedAt: Instant,
        officer: String,
        recordedAt: Instant,
        outcome: OutcomeLabel,
    ): SuspiciousTransactionReport {
        requireState(ReportState.APPROVED)
        if (officer.isBlank()) throw MakerCheckerViolation("a filing is recorded by a named officer")
        require(reference.isNotBlank() && reference.length <= MAX_REFERENCE) { "filing reference is required and bounded" }
        require(!filedAt.isAfter(recordedAt)) { "a filing cannot be recorded before it happened" }
        require(!filedAt.isBefore(checkNotNull(approvedAt))) { "a report cannot be filed before it was approved" }
        return copy(
            state = ReportState.FILED,
            filingReference = reference.trim(),
            filedAt = filedAt,
            filingRecordedBy = officer,
            filingRecordedAt = recordedAt,
            outcome = outcome,
            updatedAt = recordedAt,
        )
    }

    fun withdraw(by: String, at: Instant, outcome: OutcomeLabel): SuspiciousTransactionReport {
        if (state != ReportState.DRAFT && state != ReportState.APPROVED) {
            throw ReportStateViolation("report $id is $state and cannot be withdrawn")
        }
        if (by.isBlank()) throw MakerCheckerViolation("a withdrawal is made by a named officer")
        return copy(state = ReportState.WITHDRAWN, withdrawnBy = by, withdrawnAt = at, outcome = outcome, updatedAt = at)
    }

    private fun requireState(required: ReportState) {
        if (state != required) throw ReportStateViolation("report $id is $state, required $required")
    }

    companion object {
        const val MAX_NARRATIVE = 8_000
        const val MAX_LINKS = 200
        const val MAX_REFERENCE = 128

        fun draft(
            id: UUID,
            partyId: UUID,
            narrative: String,
            caseIds: List<UUID>,
            movements: List<ReportedMovement>,
            draftedBy: String,
            at: Instant,
        ) = SuspiciousTransactionReport(
            id = id,
            partyId = partyId,
            state = ReportState.DRAFT,
            narrative = narrative.trim(),
            caseIds = caseIds,
            movements = movements,
            draftedBy = draftedBy,
            draftedAt = at,
        )
    }
}
