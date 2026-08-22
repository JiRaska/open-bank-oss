// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvents
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID

class KycCaseNotFoundException(id: UUID) : RuntimeException("KYC case not found: $id")

/**
 * A party already has an in-flight (non-terminal) KYC case. Raised by the operator-facing
 * [KycService.openCase]; mapped to HTTP 409 Conflict. The event-driven [KycService.openCaseForParty]
 * stays idempotent and never throws this.
 */
class KycCaseConflictException(val partyId: UUID, val existingCaseId: UUID) :
    RuntimeException("Party $partyId already has an active KYC case: $existingCaseId")

/**
 * Raised when the approval/rejection reason is too short to meet the regulatory audit trail
 * requirement (ČNB AML/KYC §8, AML Act No. 253/2008 Coll.).
 * Minimum [KycService.MIN_REASON_LENGTH] characters required.
 * Mapped to HTTP 422 Unprocessable Entity.
 */
class InvalidApprovalReasonException(reason: String) :
    RuntimeException(
        "reason must be at least ${KycService.MIN_REASON_LENGTH} characters for the regulatory " +
            "audit trail (ČNB AML/KYC §8); got ${reason.length} character(s)",
    )

/**
 * Raised when a state transition is attempted on a KYC case that is not in the required state.
 *
 * Approve and reject are only valid from [KycCaseStatus.UNDER_REVIEW]. Attempting either
 * operation on a case in any other state (e.g. still OPEN, already APPROVED, EXPIRED) raises
 * this exception, mapped to HTTP 422 Unprocessable Entity.
 *
 * This guards the ČNB four-eyes mandate (ADR-0068): only cases that have passed all automated
 * checks and reached UNDER_REVIEW may be submitted for human approval.
 */
class InvalidStateTransitionException(
    val caseId: UUID,
    val currentStatus: KycCaseStatus,
    val attemptedAction: String,
) : RuntimeException(
    "Cannot $attemptedAction KYC case $caseId: current status is $currentStatus, " +
        "expected UNDER_REVIEW (ADR-0068 four-eyes gate)",
)

/** Outcome of an idempotent open: the case plus whether it was newly created (vs reused). */
data class KycCaseResult(val case: KycCase, val created: Boolean)

@ApplicationScoped
class KycService {

    companion object {
        /** Minimum reason length enforced by the ČNB four-eyes audit trail mandate (AML Act §8). */
        const val MIN_REASON_LENGTH = 10
    }

    @Inject lateinit var repo: KycCaseRepository

    @Inject lateinit var metrics: DomainMetrics

    @Inject lateinit var clock: Clock

    // A KYC case carries no subject-type dimension yet (no partyType/entityType on KycCase —
    // it only references a partyId). Emit the metric with a single stable, bounded `type` tag so
    // the series is still collected; revisit once the case gains an individual|business field.
    private val caseType = "unknown"

    // Sandbox-only straight-through processing (ADR-0116 §4): when true, a case opened from a
    // PARTY_CREATED event is auto-evaluated (all checks PASSED) and approved, so onboarding
    // activates without an operator. MUST stay false in production (four-eyes, ADR-0068).
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "openbank.kyc.auto-approve", defaultValue = "false")
    var autoApprove: Boolean = false

    /**
     * Operator-facing open. Rejects a duplicate with [KycCaseConflictException] (→ 409) when the
     * party already has an active case, instead of letting the V5 partial unique index surface an
     * opaque 500. The idempotent reuse-on-retry behaviour lives in [openCaseForParty] (event path).
     */
    suspend fun openCase(partyId: UUID): KycCase {
        repo.findActiveByPartyId(partyId)?.let { throw KycCaseConflictException(partyId, it.id) }
        return createCase(partyId)
    }

    /** Insert a fresh OPEN case with the mandatory checks, persist, count and publish. */
    private suspend fun createCase(partyId: UUID): KycCase {
        val case = KycCase(
            id = UUID.randomUUID(),
            partyId = partyId,
            status = KycCaseStatus.OPEN,
            riskLevel = RiskLevel.MEDIUM,
            assignedTo = null,
            checks = listOf(
                newCheck(CheckType.IDENTITY),
                newCheck(CheckType.ADDRESS),
                newCheck(CheckType.PEP_SCREENING),
                newCheck(CheckType.SANCTIONS_SCREENING),
            ),
            notes = null,
            reviewedBy = null,
            reviewedAt = null,
            expiresAt = Instant.now(clock).plusSeconds(30 * 24 * 3600),
            createdAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        )
        val saved = repo.save(case, KycEvents.caseOpened(case, Instant.now(clock)))
        metrics.kycSubmitted(caseType)
        return saved
    }

    /**
     * Idempotently ensure an active KYC case exists for [partyId]. Invoked by the PARTY_CREATED
     * event consumer so a case auto-opens the moment a party is onboarded, instead of an operator
     * opening it by hand.
     *
     * Two layers make this safe under at-least-once delivery and topic replay:
     *  1. the common path checks [KycCaseRepository.findActiveByPartyId] first and reuses an open case;
     *  2. if two inserts race (replay, or a future multi-pod scale-out), the partial unique index
     *     `uq_kyc_cases_active_party` (V5) rejects the loser — we catch that, re-read, and return
     *     the case the winner created. So a redelivered event never creates a duplicate open case.
     *
     * Returns a [KycCaseResult] flagging whether a new case was actually created (vs an idempotent
     * reuse) so the consumer can log the two paths distinctly.
     */
    suspend fun openCaseForParty(partyId: UUID): KycCaseResult {
        repo.findActiveByPartyId(partyId)?.let { return KycCaseResult(it, created = false) }
        val opened = try {
            createCase(partyId)
        } catch (e: Exception) {
            // A concurrent insert won the uq_kyc_cases_active_party race. Treat as idempotent:
            // re-read and return the existing case. Rethrow only if it failed for another reason.
            val existing = repo.findActiveByPartyId(partyId) ?: throw e
            return KycCaseResult(existing, created = false)
        }
        // Sandbox straight-through: pass every check and approve so the party clears KYC
        // automatically. No-op (and the four-eyes approve endpoint remains the only path) in prod.
        val settled = if (autoApprove) autoEvaluateAndApprove(opened) else opened
        return KycCaseResult(settled, created = true)
    }

    private suspend fun autoEvaluateAndApprove(case: KycCase): KycCase {
        val passedChecks = case.checks.map {
            it.copy(status = CheckStatus.PASSED, result = "sandbox-auto", performedAt = Instant.now(clock))
        }
        val cleared = case.copy(
            status = KycCaseStatus.APPROVED,
            checks = passedChecks,
            reviewedBy = "sandbox-auto-approval",
            reviewedAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        )
        val saved = repo.update(cleared, KycEvents.caseApproved(cleared, Instant.now(clock)))
        return saved
    }

    suspend fun getCase(id: UUID): KycCase = repo.findById(id) ?: throw KycCaseNotFoundException(id)

    /**
     * List KYC cases, optionally filtered by [status]. When [status] is null all cases are
     * returned. When provided the result is scoped to that funnel stage for the onboarding
     * cockpit (ADR-0068).
     */
    suspend fun listCases(page: Int, size: Int, status: KycCaseStatus? = null): List<KycCase> =
        if (status != null) repo.listByStatus(status, page, size) else repo.listAll(page, size)

    suspend fun countCases(status: KycCaseStatus? = null): Long =
        if (status != null) repo.countByStatus(status) else repo.countAll()

    suspend fun getCaseByParty(partyId: UUID): KycCase? = repo.findByPartyId(partyId)

    suspend fun updateCheckStatus(caseId: UUID, checkType: CheckType, status: CheckStatus, result: String?): KycCase {
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        val updatedChecks = case.checks.map {
            if (it.checkType ==
                checkType
            ) {
                it.copy(status = status, result = result, performedAt = Instant.now(clock))
            } else {
                it
            }
        }
        val allPassed = updatedChecks.all { it.status == CheckStatus.PASSED }
        val anyFailed = updatedChecks.any { it.status == CheckStatus.FAILED }
        val newStatus = when {
            allPassed -> KycCaseStatus.UNDER_REVIEW
            anyFailed -> KycCaseStatus.REJECTED
            else -> case.status
        }
        val updated = case.copy(checks = updatedChecks, status = newStatus, updatedAt = Instant.now(clock))
        val saved = if (newStatus != case.status) {
            repo.update(updated, KycEvents.caseStatusChanged(updated, Instant.now(clock)))
        } else {
            repo.update(updated)
        }
        return saved
    }

    /**
     * Apply the outcome of a PEP (Politically Exposed Person) screen — first increment of
     * ADR-0116's "external watchlist: Planned" delivery note. Screens against
     * `openbank-sanctions-service`'s already-imported OpenSanctions `PEP_GLOBAL` list only; this
     * is a free-data-source PEP check, not a paid commercial vendor feed, not identity-document
     * verification, and not continuous real-time monitoring (case-open time only in this
     * increment — periodic re-screening needs the Temporal workflow already flagged in ADR-0116
     * §5, tracked separately).
     *
     * A [PepScreeningStatus.MATCH] or [PepScreeningStatus.POTENTIAL_MATCH] sets the
     * `PEP_SCREENING` check to [CheckStatus.MANUAL_REVIEW] (never auto-reject — a PEP hit needs
     * additional four-eyes scrutiny, per ADR-0116, not an automated verdict) and escalates
     * [KycCase.riskLevel] to at least [RiskLevel.HIGH] so the operator queue surfaces it.
     * [PepScreeningStatus.CLEAR] passes the check without touching the risk tier.
     * [PepScreeningStatus.UNAVAILABLE] (sanctions-service unreachable) also routes to
     * MANUAL_REVIEW rather than a silent PASSED, so a transient outage can never look like a
     * clean screen.
     */
    suspend fun applyPepScreeningResult(
        caseId: UUID,
        screeningStatus: PepScreeningStatus,
        matchScore: Double,
        matchedName: String?,
    ): KycCase {
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)

        val checkStatus = pepCheckStatusFor(screeningStatus)
        val resultSummary = pepResultSummaryFor(screeningStatus, matchScore, matchedName)
        val updatedChecks = case.checks.map {
            if (it.checkType == CheckType.PEP_SCREENING) {
                it.copy(
                    status = checkStatus,
                    result = resultSummary,
                    provider = "openbank-sanctions-service:PEP_GLOBAL",
                    performedAt = Instant.now(clock),
                )
            } else {
                it
            }
        }

        val escalatedRisk = if (isPepHit(screeningStatus)) escalate(case.riskLevel) else case.riskLevel
        val newStatus = nextStatusFor(case.status, updatedChecks)

        val updated = case.copy(
            checks = updatedChecks,
            status = newStatus,
            riskLevel = escalatedRisk,
            updatedAt = Instant.now(clock),
        )
        val changed = newStatus != case.status || escalatedRisk != case.riskLevel
        val saved = if (changed) {
            repo.update(updated, KycEvents.caseStatusChanged(updated, Instant.now(clock)))
        } else {
            repo.update(updated)
        }
        return saved
    }

    private fun isPepHit(screeningStatus: PepScreeningStatus): Boolean =
        screeningStatus == PepScreeningStatus.MATCH || screeningStatus == PepScreeningStatus.POTENTIAL_MATCH

    /** Never auto-reject on a PEP hit — additional four-eyes scrutiny, not an automated verdict (ADR-0116). */
    private fun pepCheckStatusFor(screeningStatus: PepScreeningStatus): CheckStatus = when (screeningStatus) {
        PepScreeningStatus.CLEAR -> CheckStatus.PASSED
        PepScreeningStatus.MATCH, PepScreeningStatus.POTENTIAL_MATCH, PepScreeningStatus.UNAVAILABLE ->
            CheckStatus.MANUAL_REVIEW
    }

    private fun pepResultSummaryFor(screeningStatus: PepScreeningStatus, matchScore: Double, matchedName: String?) =
        when (screeningStatus) {
            PepScreeningStatus.CLEAR -> "openbank-sanctions-service:PEP_GLOBAL clear (score=$matchScore)"
            PepScreeningStatus.MATCH ->
                "openbank-sanctions-service:PEP_GLOBAL match — \"$matchedName\" (score=$matchScore)"
            PepScreeningStatus.POTENTIAL_MATCH ->
                "openbank-sanctions-service:PEP_GLOBAL potential match — \"$matchedName\" (score=$matchScore)"
            PepScreeningStatus.UNAVAILABLE ->
                "openbank-sanctions-service:PEP_GLOBAL unavailable — routed to manual review, not auto-cleared"
        }

    /** Same all-passed/any-failed transition rule [updateCheckStatus] uses, reused for a PEP-check update. */
    private fun nextStatusFor(currentStatus: KycCaseStatus, checks: List<KycCheck>): KycCaseStatus {
        val allPassed = checks.all { it.status == CheckStatus.PASSED }
        val anyFailed = checks.any { it.status == CheckStatus.FAILED }
        return when {
            allPassed -> KycCaseStatus.UNDER_REVIEW
            anyFailed -> KycCaseStatus.REJECTED
            else -> currentStatus
        }
    }

    /** Escalate risk one notch on a PEP hit (never downgrades); floors at [RiskLevel.HIGH] (ADR-0116). */
    private fun escalate(current: RiskLevel): RiskLevel = when (current) {
        RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH -> RiskLevel.HIGH
        RiskLevel.VERY_HIGH -> RiskLevel.VERY_HIGH
    }

    suspend fun approve(caseId: UUID, reviewedBy: String): KycCase {
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        val updated = case.copy(
            status = KycCaseStatus.APPROVED,
            reviewedBy = reviewedBy,
            reviewedAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        )
        val saved = repo.update(updated, KycEvents.caseApproved(updated, Instant.now(clock)))
        metrics.kycVerdict(caseType, "approved")
        return saved
    }

    suspend fun reject(caseId: UUID, reviewedBy: String, reason: String): KycCase {
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        val updated = case.copy(
            status = KycCaseStatus.REJECTED,
            reviewedBy = reviewedBy,
            reviewedAt = Instant.now(clock),
            notes = reason,
            updatedAt = Instant.now(clock),
        )
        val saved = repo.update(updated, KycEvents.caseRejected(updated, Instant.now(clock)))
        metrics.kycVerdict(caseType, "rejected")
        return saved
    }

    /**
     * Approve a KYC case with a mandatory reason for the regulatory audit trail.
     *
     * Only cases in [KycCaseStatus.UNDER_REVIEW] may be approved (four-eyes gate, ADR-0068).
     * The [approvedBy] identity must come from the authenticated security context — never from
     * the request body — to satisfy the ČNB AML/KYC §8 four-eyes mandate.
     *
     * On success:
     * 1. The case status transitions UNDER_REVIEW → APPROVED.
     * 2. The [reason] and [approvedBy] are recorded as [KycCase.notes] and [KycCase.reviewedBy].
     * 3. A KYC_CASE_APPROVED row is written to `kyc_outbox` in the same transaction.
     *
     * @throws KycCaseNotFoundException when [caseId] does not exist.
     * @throws InvalidStateTransitionException when the case is not in UNDER_REVIEW status.
     */
    suspend fun approveCase(caseId: UUID, approvedBy: String, reason: String): KycCase {
        validateReason(reason)
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        if (case.status != KycCaseStatus.UNDER_REVIEW) {
            throw InvalidStateTransitionException(caseId, case.status, "approve")
        }
        val updated = case.copy(
            status = KycCaseStatus.APPROVED,
            reviewedBy = approvedBy,
            reviewedAt = Instant.now(clock),
            notes = reason,
            updatedAt = Instant.now(clock),
        )
        val saved = repo.update(updated, KycEvents.caseApproved(updated, Instant.now(clock)))
        metrics.kycVerdict(caseType, "approved")
        return saved
    }

    /**
     * Reject a KYC case with a mandatory reason for the regulatory audit trail.
     *
     * Only cases in [KycCaseStatus.UNDER_REVIEW] may be rejected (four-eyes gate, ADR-0068).
     * The [rejectedBy] identity must come from the authenticated security context — never from
     * the request body — to satisfy the ČNB AML/KYC §8 four-eyes mandate.
     *
     * On success:
     * 1. The case status transitions UNDER_REVIEW → REJECTED.
     * 2. The [reason] and [rejectedBy] are recorded as [KycCase.notes] and [KycCase.reviewedBy].
     * 3. A KYC_CASE_REJECTED row is written to `kyc_outbox` in the same transaction.
     *
     * @throws KycCaseNotFoundException when [caseId] does not exist.
     * @throws InvalidStateTransitionException when the case is not in UNDER_REVIEW status.
     */
    suspend fun rejectCase(caseId: UUID, rejectedBy: String, reason: String): KycCase {
        validateReason(reason)
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        if (case.status != KycCaseStatus.UNDER_REVIEW) {
            throw InvalidStateTransitionException(caseId, case.status, "reject")
        }
        val updated = case.copy(
            status = KycCaseStatus.REJECTED,
            reviewedBy = rejectedBy,
            reviewedAt = Instant.now(clock),
            notes = reason,
            updatedAt = Instant.now(clock),
        )
        val saved = repo.update(updated, KycEvents.caseRejected(updated, Instant.now(clock)))
        metrics.kycVerdict(caseType, "rejected")
        return saved
    }

    /**
     * Guard the ČNB audit trail minimum: reasons shorter than [MIN_REASON_LENGTH] characters
     * are rejected before any DB interaction. Extracted to keep approveCase/rejectCase within
     * the two-throw limit enforced by the detekt ThrowsCount rule.
     */
    private fun validateReason(reason: String) {
        if (reason.length < MIN_REASON_LENGTH) throw InvalidApprovalReasonException(reason)
    }

    private fun newCheck(type: CheckType) = KycCheck(
        id = UUID.randomUUID(),
        caseId = UUID.randomUUID(),
        checkType = type,
        status = CheckStatus.PENDING,
        result = null,
        provider = null,
        performedAt = null,
        createdAt = Instant.now(clock),
    )
}
