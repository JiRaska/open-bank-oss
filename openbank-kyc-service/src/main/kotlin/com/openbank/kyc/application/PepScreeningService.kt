// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.PepScreeningPort
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.PepScreeningUnavailableException
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates the first-increment PEP (Politically Exposed Person) check (ADR-0116 delivery
 * note, "External watchlist: Planned"): screens a party's name against
 * `openbank-sanctions-service`'s `PEP_GLOBAL` list type via [PepScreeningPort] and applies the
 * outcome to the case's `PEP_SCREENING` check ([KycService.applyPepScreeningResult]).
 *
 * Deliberately NOT a paid commercial vendor feed (Refinitiv/ComplyAdvantage/World-Check/etc), NOT
 * identity-document verification, and NOT continuous/real-time monitoring — screening runs at
 * case-open time only (see [screenCaseOnOpen]) or when explicitly re-triggered
 * ([screenExistingCase], the operator-facing re-screen endpoint). True periodic re-screening
 * needs the Temporal scheduled workflow ADR-0116 §5 already flags as a separate follow-up.
 */
@ApplicationScoped
class PepScreeningService {

    @Inject lateinit var screeningPort: PepScreeningPort

    @Inject lateinit var kycService: KycService

    private val log = Logger.getLogger(PepScreeningService::class.java)

    /**
     * Screen [partyName] for [caseId] and apply the result to the case's PEP_SCREENING check.
     * Called both from the PARTY_CREATED consumer (case-open time) and from the operator-facing
     * re-screen endpoint. Never throws past this boundary — a downstream outage is applied as
     * [PepScreeningStatus.UNAVAILABLE] (→ MANUAL_REVIEW), not a failed case open/re-screen.
     */
    suspend fun screenCase(caseId: UUID, partyName: String): KycCase {
        val replaces = kycService.getCase(caseId).checks
            .firstOrNull { it.checkType == CheckType.PEP_SCREENING }?.performedAt
        val (status, score, matchedName) = try {
            val result = screeningPort.screenForPep(name = partyName, idempotencyKey = rescreenKey(caseId, replaces))
            Triple(result.status, result.matchScore, result.matchedName)
        } catch (e: PepScreeningUnavailableException) {
            log.warnf(e, "PEP screening unavailable for KYC case %s — routing to manual review", caseId)
            Triple(PepScreeningStatus.UNAVAILABLE, 0.0, null)
        }
        return kycService.applyPepScreeningResult(caseId, status, score, matchedName)
    }

    companion object {
        /**
         * Idempotency key of an operator re-screen: the case plus the time of the PEP result the
         * re-screen is about to replace ([replaces], null when none was ever recorded).
         *
         * sanctions-service answers a repeated key with the check it stored the first time,
         * before consulting any list. One key per case therefore made every re-screen a replay
         * of the first answer — it could never see a refreshed list — while a fresh `performedAt`
         * made it look like a new screen. With the replaced result in the key, a re-screen after
         * a recorded result is a new screen, and a request retried before its result was recorded
         * still sends the same key and is replayed rather than duplicated.
         */
        fun rescreenKey(caseId: UUID, replaces: Instant?): String =
            "kyc-pep-$caseId" + (replaces?.let { "-${it.toEpochMilli()}" } ?: "")
    }
}
