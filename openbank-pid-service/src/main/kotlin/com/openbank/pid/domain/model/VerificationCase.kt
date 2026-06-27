// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Why a manual identity-verification case was triggered (ADR-0072 §1).
 *
 * Lives in the domain layer because the case aggregate is built around it; the resolution
 * use case ([com.openbank.pid.application.usecase.IdentityResolutionService]) imports it from here.
 */
enum class VerificationTrigger {
    /** Same RČ blind index but divergent core attributes — possible RČ collision or data-entry error. */
    RN_COLLISION,

    /** No RČ, but a normalized (name + birthdate + birthplace) candidate match was found. */
    NAMESAKE_CANDIDATE,

    /**
     * No RČ and no exact match-key, but a probabilistic (Fellegi-Sunter) score put one or more
     * existing parties in the gray zone or above — a likely fuzzy duplicate (typo, diacritics,
     * day-of-birth slip). Never auto-merged; routed here for a human decision (ADR-0072 tier-2′).
     */
    PROBABILISTIC_CANDIDATE,
}

/** Lifecycle of a four-eyes identity-verification case. */
enum class VerificationCaseStatus {
    /** Opened, no approver has proposed a verdict yet. */
    OPEN,

    /** A first approver proposed a verdict; awaiting a distinct second approver to concur (ADR-0030). */
    AWAITING_SECOND_APPROVAL,

    /** Two distinct approvers concurred; [VerificationCase.finalVerdict] is applied to future resolves. */
    DECIDED,
}

/** The operator's adjudication of an ambiguous applicant. */
enum class CaseVerdict {
    /** The applicant IS one of the candidate parties — future resolves return MatchExisting. */
    LINK_TO_EXISTING,

    /** The applicant is a genuinely distinct person — future resolves return NoMatch (may create). */
    DISTINCT_NEW,

    /** The applicant is rejected (e.g. suspected fraud) — future resolves stay blocked. */
    REJECT,
}

/** The applicant's own attributes, captured for the operator to adjudicate. The RČ is never stored here. */
data class ApplicantSnapshot(
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate,
    val birthplace: String?,
    val nationalities: List<String>,
)

/** Raised when a case transition is attempted from an illegal state or with an illegal actor. */
class IllegalCaseTransition(message: String) : RuntimeException(message)

/**
 * A durable four-eyes identity-verification case (ADR-0072 §1 / ADR-0030).
 *
 * State machine: OPEN → (proposeFirst) → AWAITING_SECOND_APPROVAL → (confirmSecond, distinct approver,
 * concurring verdict) → DECIDED. A non-concurring or wrong-state transition throws
 * [IllegalCaseTransition]; [reopen] resets an AWAITING case back to OPEN (clearing the first proposal)
 * so a disagreeing reviewer can withdraw it.
 *
 * The aggregate is pure (no framework imports); the service maps its exceptions to HTTP and persists it.
 */
data class VerificationCase(
    val id: UUID,
    val dedupKey: String,
    val trigger: VerificationTrigger,
    val status: VerificationCaseStatus,
    val applicant: ApplicantSnapshot,
    /** Keyed blind index of the RČ (RN_COLLISION only); null for namesake cases. Never the plaintext. */
    val blindIndex: String?,
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
) {
    /** First approver proposes a verdict. Requires OPEN; LINK_TO_EXISTING must name a candidate party. */
    fun proposeFirst(
        approver: String,
        verdict: CaseVerdict,
        linkPartyId: UUID?,
        notes: String?,
        now: Instant,
    ): VerificationCase {
        if (status != VerificationCaseStatus.OPEN) {
            throw IllegalCaseTransition("case $id is $status, not OPEN — cannot propose a first verdict")
        }
        validateLink(verdict, linkPartyId)
        return copy(
            status = VerificationCaseStatus.AWAITING_SECOND_APPROVAL,
            firstApprover = approver,
            firstVerdict = verdict,
            firstLinkPartyId = linkPartyId,
            firstNotes = notes,
            firstAt = now,
            updatedAt = now,
        )
    }

    /**
     * A distinct second approver concurs, deciding the case (ADR-0030 four-eyes). Requires
     * AWAITING_SECOND_APPROVAL, a different approver, and the SAME verdict (and link target) as the first.
     */
    @Suppress("ThrowsCount") // three independent four-eyes guard clauses, each with a distinct message
    fun confirmSecond(approver: String, verdict: CaseVerdict, linkPartyId: UUID?, now: Instant): VerificationCase {
        if (status != VerificationCaseStatus.AWAITING_SECOND_APPROVAL) {
            throw IllegalCaseTransition("case $id is $status — no first proposal awaiting confirmation")
        }
        if (approver == firstApprover) {
            throw IllegalCaseTransition("four-eyes violation: $approver already cast the first vote on case $id")
        }
        if (verdict != firstVerdict || linkPartyId != firstLinkPartyId) {
            throw IllegalCaseTransition(
                "second approver disagrees with the first proposal on case $id — reopen to re-adjudicate",
            )
        }
        return copy(
            status = VerificationCaseStatus.DECIDED,
            secondApprover = approver,
            secondAt = now,
            finalVerdict = verdict,
            finalLinkPartyId = linkPartyId,
            decidedAt = now,
            updatedAt = now,
        )
    }

    /** Withdraw an awaiting first proposal, returning the case to OPEN. */
    fun reopen(now: Instant): VerificationCase {
        if (status != VerificationCaseStatus.AWAITING_SECOND_APPROVAL) {
            throw IllegalCaseTransition("case $id is $status — only an AWAITING case can be reopened")
        }
        return copy(
            status = VerificationCaseStatus.OPEN,
            firstApprover = null,
            firstVerdict = null,
            firstLinkPartyId = null,
            firstNotes = null,
            firstAt = null,
            updatedAt = now,
        )
    }

    @Suppress("ThrowsCount") // distinct validation messages for each illegal link combination
    private fun validateLink(verdict: CaseVerdict, linkPartyId: UUID?) {
        if (verdict == CaseVerdict.LINK_TO_EXISTING) {
            if (linkPartyId == null) {
                throw IllegalCaseTransition("LINK_TO_EXISTING requires a linkPartyId")
            }
            if (linkPartyId !in candidatePartyIds) {
                throw IllegalCaseTransition("linkPartyId $linkPartyId is not a candidate of case $id")
            }
        } else if (linkPartyId != null) {
            throw IllegalCaseTransition("linkPartyId is only valid with verdict LINK_TO_EXISTING")
        }
    }

    companion object {
        /** Open a fresh case in OPEN state. */
        @Suppress("LongParameterList") // a fresh aggregate's full attribute set
        fun open(
            id: UUID,
            dedupKey: String,
            trigger: VerificationTrigger,
            applicant: ApplicantSnapshot,
            blindIndex: String?,
            candidatePartyIds: List<UUID>,
            now: Instant,
        ): VerificationCase = VerificationCase(
            id = id,
            dedupKey = dedupKey,
            trigger = trigger,
            status = VerificationCaseStatus.OPEN,
            applicant = applicant,
            blindIndex = blindIndex,
            candidatePartyIds = candidatePartyIds,
            firstApprover = null,
            firstVerdict = null,
            firstLinkPartyId = null,
            firstNotes = null,
            firstAt = null,
            secondApprover = null,
            secondAt = null,
            finalVerdict = null,
            finalLinkPartyId = null,
            decidedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
