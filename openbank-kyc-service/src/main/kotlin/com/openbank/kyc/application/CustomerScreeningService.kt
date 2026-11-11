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
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
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
 * decision, or an earlier screening result, with a replay of the first answer.
 */
@ApplicationScoped
class CustomerScreeningService(
    private val screeningPort: CustomerScreeningPort,
    private val repo: KycCaseRepository,
    private val kycService: KycService,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(CustomerScreeningService::class.java)

    suspend fun screenOnOpen(case: KycCase, partyName: String): KycCase {
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
        val afterSanctions = applySanctions(case.id, result?.sanctions)
        if (afterSanctions.checks.none { it.checkType == CheckType.PEP_SCREENING }) return afterSanctions
        return kycService.applyPepScreeningResult(
            case.id,
            result?.pep?.status ?: PepScreeningStatus.UNAVAILABLE,
            result?.pep?.matchScore ?: 0.0,
            result?.pep?.matchedName,
        )
    }

    private suspend fun applySanctions(caseId: UUID, outcome: SanctionsScreeningOutcome?): KycCase {
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
    }
}
