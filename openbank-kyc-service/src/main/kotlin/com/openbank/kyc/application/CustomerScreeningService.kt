// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.CustomerScreeningPort
import com.openbank.kyc.application.port.out.CustomerScreeningRequest
import com.openbank.kyc.application.port.out.CustomerScreeningUnavailableException
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.SanctionsScreeningOutcome
import com.openbank.kyc.application.port.out.SanctionsScreeningStatus
import com.openbank.kyc.application.port.out.UnscreenedCaseStore
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvents
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.kyc.domain.model.SubjectType
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Automated customer screening when a KYC case opens (ADR-0116, issue #15723): one call to
 * sanctions-service's CUSTOMER profile settles the `SANCTIONS_SCREENING` check and feeds the
 * existing PEP handling.
 *
 *  - no sanctions match: the check is PASSED;
 *  - a match or potential match: the check goes to MANUAL_REVIEW and the risk tier is raised. The
 *    case can reach UNDER_REVIEW, and so be approved, only after a reviewer decides the check;
 *  - no result (sanctions-service unreachable): the check stays PENDING. It is never passed on a
 *    missing result, and a PENDING check blocks approval exactly like an undecided one.
 *
 * Only a PENDING check is settled here. A redelivered event must not replace a reviewer's
 * decision, or an earlier screening result, with a replay of the first answer. The same holds for
 * the PEP check: it is written only while it still awaits a screen ([awaitsScreen]).
 *
 * A screen that could not be obtained is retried by
 * [com.openbank.kyc.infrastructure.screening.UnscreenedCaseRetryJob] through this same method,
 * with the same idempotency key — so if the provider stored a result whose response was lost, the
 * retry is answered with that result instead of creating a second screen. The retry needs the
 * party's name, which only the opening event carried; it is kept in [UnscreenedCaseStore] until
 * the check is settled.
 */
@ApplicationScoped
class CustomerScreeningService(
    private val screeningPort: CustomerScreeningPort,
    private val repo: KycCaseRepository,
    private val kycService: KycService,
    private val clock: Clock,
    private val unscreened: UnscreenedCaseStore,
) {

    private val log = Logger.getLogger(CustomerScreeningService::class.java)

    suspend fun screenOnOpen(case: KycCase, partyName: String): KycCase {
        // An erased party (GDPR Art. 17) is not screened. This read is what keeps its name from
        // being SENT; it does not protect the write that follows the provider's answer — an
        // erasure can land while the provider is being asked. The write protects itself
        // (KycCaseRepository.update), so there is deliberately no second check after the call.
        if (repo.isErased(case.id)) return case
        val result = try {
            screeningPort.screenCustomer(
                CustomerScreeningRequest(
                    name = partyName,
                    subjectId = case.partyId.toString(),
                    organisation = case.subjectType == SubjectType.BUSINESS,
                    idempotencyKey = "kyc-screen-${case.id}",
                ),
            )
        } catch (e: CustomerScreeningUnavailableException) {
            log.warnf(e, "Customer screening unavailable for KYC case %s — sanctions check stays PENDING", case.id)
            null
        }
        // Null: the case was erased after the read above (the write refused it). Nothing was
        // stored and nothing follows — no name kept for a retry, no PEP result.
        val afterSanctions = applySanctions(case.id, result?.sanctions) ?: return case
        keepOrForgetName(afterSanctions, partyName)
        val pep = afterSanctions.checks.firstOrNull { it.checkType == CheckType.PEP_SCREENING }
        if (pep == null || !pep.awaitsScreen(hasResult = result != null)) return afterSanctions
        return kycService.applyPepScreeningResult(
            case.id,
            result?.pep?.status ?: PepScreeningStatus.UNAVAILABLE,
            result?.pep?.matchScore ?: 0.0,
            result?.pep?.matchedName,
        )
    }

    /**
     * The name is kept exactly while the sanctions check is still PENDING and unscreened on an
     * open case — the state a retry can change. In every other state it is forgotten.
     */
    private suspend fun keepOrForgetName(case: KycCase, partyName: String) {
        val sanctions = case.checks.firstOrNull { it.checkType == CheckType.SANCTIONS_SCREENING }
        if (sanctions != null && sanctions.isUnscreened() && !case.status.isTerminal) {
            unscreened.retainForRetry(case.id, partyName, Instant.now(clock))
        } else {
            unscreened.release(case.id)
        }
    }

    /**
     * A PEP check may be written by a screen only while no result and no reviewer has settled it:
     * it is still PENDING, or it was put under review by an earlier outage and this call brings an
     * actual result. Anything else is a decision that a retry or a redelivered event must keep.
     */
    private fun KycCheck.awaitsScreen(hasResult: Boolean): Boolean =
        status == CheckStatus.PENDING || (hasResult && result == KycService.PEP_UNAVAILABLE_RESULT)

    /** @return the case as it now stands, or null when the write was refused because it was erased. */
    private suspend fun applySanctions(caseId: UUID, outcome: SanctionsScreeningOutcome?): KycCase? {
        val case = repo.findById(caseId) ?: throw KycCaseNotFoundException(caseId)
        val current = case.checks.firstOrNull { it.checkType == CheckType.SANCTIONS_SCREENING }
        if (current == null || current.status != CheckStatus.PENDING) return case

        val now = Instant.now(clock)
        val settled = when (outcome?.status) {
            null -> current.copy(result = "$PROVIDER unavailable — not screened, check stays pending")
            SanctionsScreeningStatus.CLEAR -> current.copy(
                status = CheckStatus.PASSED,
                result = "$PROVIDER clear (score=${outcome.matchScore})",
                provider = PROVIDER,
                performedAt = now,
            )
            SanctionsScreeningStatus.MATCH, SanctionsScreeningStatus.POTENTIAL_MATCH -> current.copy(
                status = CheckStatus.MANUAL_REVIEW,
                result = "$PROVIDER ${outcome.status.name.lowercase().replace('_', ' ')} — " +
                    "\"${outcome.matchedName}\" (score=${outcome.matchScore})",
                provider = PROVIDER,
                performedAt = now,
            )
        }
        val checks = case.checks.map { if (it.id == current.id) settled else it }
        val risk = when (outcome?.status) {
            SanctionsScreeningStatus.MATCH -> RiskLevel.VERY_HIGH
            SanctionsScreeningStatus.POTENTIAL_MATCH -> maxOf(case.riskLevel, RiskLevel.HIGH)
            else -> case.riskLevel
        }
        val status = if (checks.all { it.status == CheckStatus.PASSED }) KycCaseStatus.UNDER_REVIEW else case.status
        val updated = case.copy(checks = checks, riskLevel = risk, status = status, updatedAt = now)
        return if (status != case.status || risk != case.riskLevel) {
            repo.update(updated, KycEvents.caseStatusChanged(updated, now))
        } else {
            repo.update(updated)
        }
    }

    companion object {
        /** Recorded on a check settled by an actual screen; the sandbox auto-approval requires it. */
        const val PROVIDER = KycCase.CUSTOMER_SCREENING_PROVIDER

        /** PENDING with no provider: no screen has ever settled this check. */
        fun KycCheck.isUnscreened(): Boolean = status == CheckStatus.PENDING && provider == null
    }
}
