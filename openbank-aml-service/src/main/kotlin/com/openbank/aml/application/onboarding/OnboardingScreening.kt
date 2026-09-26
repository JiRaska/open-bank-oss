// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.onboarding

import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import java.util.UUID

/**
 * The single code path that opens a party's onboarding AML case (ADR-0267 §2: the AML outcome is
 * the second key of the party activation gate). The PARTY_CREATED consumer and the onboarding
 * screening reconciler both go through [open], so the two can never disagree about what an
 * onboarding case is.
 *
 * Idempotent by construction: the case key is [idempotencyKey] (`<partyId>:CUSTOMER_ONBOARDING`),
 * [AmlCaseUseCase.createCase] returns the existing case for a key it has already stored, and
 * `aml_cases.idempotency_key` is `UNIQUE`. The sandbox auto-clear is skipped once the case is
 * terminal, so a repeated call changes nothing.
 */
class OnboardingScreening(private val amlUseCase: AmlCaseUseCase, private val autoClear: Boolean) {
    /** The case after [open], and whether this call auto-cleared it. */
    data class Outcome(val case: AmlCase, val autoCleared: Boolean)

    suspend fun open(partyId: UUID): Outcome {
        val case = amlUseCase.createCase(
            CreateAmlCaseCommand(
                idempotencyKey = idempotencyKey(partyId),
                partyId = partyId,
                accountId = null,
                transactionId = null,
                customerReference = "onboarding-$partyId",
                screeningType = ScreeningType.CUSTOMER_ONBOARDING,
                riskLevel = AmlRiskLevel.LOW,
                alertCode = "ONBOARDING_SCREENING",
                alertDetail = null,
                matchedEntity = null,
            ),
        )
        if (autoClear && case.status != AmlCaseStatus.CLEARED && case.status != AmlCaseStatus.BLOCKED) {
            val cleared = amlUseCase.updateDecision(
                UpdateAmlDecisionCommand(
                    caseId = case.id,
                    targetStatus = AmlCaseStatus.CLEARED,
                    decisionReason = "Sandbox auto-clear (no adverse match)",
                    assignedAnalyst = "SANDBOX_BOT",
                    decidedBy = "SANDBOX_SYSTEM",
                ),
            )
            return Outcome(cleared, autoCleared = true)
        }
        return Outcome(case, autoCleared = false)
    }

    companion object {
        /** party-service `PartyType` values that get an onboarding AML case. */
        val SCREENED_PARTY_TYPES = setOf("INDIVIDUAL", "SOLE_TRADER", "COMPANY")

        fun idempotencyKey(partyId: UUID): String = "$partyId:CUSTOMER_ONBOARDING"
    }
}
