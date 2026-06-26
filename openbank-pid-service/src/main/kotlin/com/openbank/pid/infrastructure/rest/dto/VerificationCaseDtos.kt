// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.rest.dto

import com.openbank.pid.domain.model.CaseVerdict
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import com.openbank.pid.domain.model.VerificationTrigger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Applicant attributes shown to the operator adjudicating the case. The RČ is never included. */
data class CaseApplicantResponse(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String?,
    val nationalities: List<String>,
)

/** Full four-eyes case view for the operator cockpit (ADR-0072 §1). */
data class VerificationCaseResponse(
    val id: UUID,
    val trigger: VerificationTrigger,
    val status: VerificationCaseStatus,
    val applicant: CaseApplicantResponse,
    val candidatePartyIds: List<UUID>,
    val firstApprover: String?,
    val firstVerdict: CaseVerdict?,
    val firstLinkPartyId: UUID?,
    val firstNotes: String?,
    val firstAt: Instant?,
    val secondApprover: String?,
    val secondAt: Instant?,
    val finalVerdict: CaseVerdict?,
    val finalLinkPartyId: UUID?,
    val decidedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Request body for `POST /api/v1/parties/cases/{id}/decision`. */
data class CaseDecisionRequest(
    val verdict: CaseVerdict,
    /** Required when [verdict] is LINK_TO_EXISTING — the candidate party the applicant maps to. */
    val linkPartyId: UUID? = null,
    val notes: String? = null,
)

fun VerificationCase.toResponse(): VerificationCaseResponse = VerificationCaseResponse(
    id = id,
    trigger = trigger,
    status = status,
    applicant = CaseApplicantResponse(
        givenName = applicant.givenName,
        familyName = applicant.familyName,
        birthdate = applicant.birthdate,
        birthplace = applicant.birthplace,
        nationalities = applicant.nationalities,
    ),
    candidatePartyIds = candidatePartyIds,
    firstApprover = firstApprover,
    firstVerdict = firstVerdict,
    firstLinkPartyId = firstLinkPartyId,
    firstNotes = firstNotes,
    firstAt = firstAt,
    secondApprover = secondApprover,
    secondAt = secondAt,
    finalVerdict = finalVerdict,
    finalLinkPartyId = finalLinkPartyId,
    decidedAt = decidedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
