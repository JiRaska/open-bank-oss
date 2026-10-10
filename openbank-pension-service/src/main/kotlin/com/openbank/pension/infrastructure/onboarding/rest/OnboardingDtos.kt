// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.EsgPreference
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.RecommendationReason
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.transfer.Compensation
import com.openbank.pension.domain.transfer.TransferDirection
import com.openbank.pension.domain.transfer.TransferOrigin
import com.openbank.pension.domain.transfer.TransferRequest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Request DTOs are all-nullable: Jackson leaves an absent field null, and the resource turns that
 * into a 400 with requireNotNull instead of a Kotlin NPE answering 500 (#3104).
 */

data class ScheduleDto(
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val frequency: ContributionFrequency? = null,
    val employerAmount: BigDecimal? = null,
)

data class CedingContractDto(
    val providerId: String? = null,
    val providerName: String? = null,
    val contractNumber: String? = null,
)

data class StartApplicationRequest(
    val kind: OnboardingKind? = null,
    val productLine: ProductLine? = null,
    val jurisdiction: String? = null,
    val providerEntityId: UUID? = null,
    val providerType: ProviderType? = null,
    val schedule: ScheduleDto? = null,
    val birthDate: LocalDate? = null,
    val residencyCountry: String? = null,
    val residencyEvidence: List<String?>? = null,
    /**
     * Set only by a guardian applying for a ward; honoured only after party-service verifies the
     * relation. The ACTING party is never taken from the body — it is the edge-stamped header.
     */
    val onBehalfOfPartyId: UUID? = null,
    val transferIn: CedingContractDto? = null,
)

data class QuestionnaireRequest(
    val knowledgeLevel: Int? = null,
    val experienceLevel: Int? = null,
    val riskAppetite: Int? = null,
    val lossTolerance: Int? = null,
    val financialSituationStable: Boolean? = null,
    val esgPreference: EsgPreference? = null,
    /**
     * The data-driven questionnaire (issue #12384): question id -> option code. When present the
     * legacy scale fields above are ignored; they remain for clients not yet migrated.
     */
    val answers: Map<String, String?>? = null,
    /** Inconsistency codes the participant reviewed and confirms (see GET .../questionnaire). */
    val confirmInconsistencies: List<String?>? = null,
    val language: String? = null,
)

data class ChooseStrategyRequest(
    /** Null accepts the recommendation. */
    val strategyCode: String? = null,
    val acknowledgeWarning: Boolean? = null,
    val language: String? = null,
)

data class AcceptKidRequest(val documentId: String? = null)

data class SignRequest(val scaChallengeId: String? = null)

data class ApplicationResponse(
    val applicationId: UUID,
    val kind: OnboardingKind,
    val status: String,
    val productLine: ProductLine,
    val jurisdiction: String,
    val packVersion: Int,
    val rejectionReasons: List<String>,
    val recommendedStrategy: String?,
    val chosenStrategy: String?,
    val unsuitableChoiceAcknowledged: Boolean,
    val keyInformationDocumentId: String?,
    val keyInformationDocumentSha256: String?,
    val kidAcceptedAt: Instant?,
    val signedAt: Instant?,
    val contractId: UUID?,
    val transferRequestId: UUID?,
    val coolingOffEndsOn: LocalDate?,
    val expiresOn: LocalDate,
    val closedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(a: OnboardingApplication) = ApplicationResponse(
            applicationId = a.id,
            kind = a.kind,
            status = a.status.name,
            productLine = a.productLine,
            jurisdiction = a.jurisdiction,
            packVersion = a.packVersion,
            rejectionReasons = a.rejectionReasons,
            recommendedStrategy = a.recommendedStrategy,
            chosenStrategy = a.chosenStrategy,
            unsuitableChoiceAcknowledged = a.unsuitableChoiceAcknowledged,
            keyInformationDocumentId = a.kid?.documentId,
            keyInformationDocumentSha256 = a.kid?.sha256,
            kidAcceptedAt = a.kidAcceptedAt,
            signedAt = a.signedAt,
            contractId = a.contractId,
            transferRequestId = a.transferRequestId,
            coolingOffEndsOn = a.coolingOffEndsOn,
            expiresOn = a.expiresOn,
            closedReason = a.closedReason,
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
        )
    }
}

/** Operator view: the participant is visible here, never in the client view of someone else. */
data class OperatorApplicationResponse(val partyId: UUID, val application: ApplicationResponse) {
    companion object {
        fun from(a: OnboardingApplication) = OperatorApplicationResponse(a.partyId, ApplicationResponse.from(a))
    }
}

data class RecommendationResponse(
    val recommendedStrategy: String,
    val suitableStrategies: List<String>,
    val maxRiskClass: Int,
    val yearsToRetirement: Int,
    val reasons: List<RecommendationReason>,
) {
    companion object {
        fun from(r: StrategyRecommendation) =
            RecommendationResponse(r.recommended, r.suitable, r.maxRiskClass, r.yearsToRetirement, r.reasons)
    }
}

data class QuestionnaireResponse(
    val application: ApplicationResponse,
    val recommendation: RecommendationResponse,
    /** Present for a data-driven submission: the profile with its plain "why". */
    val profile: ProfileResponse? = null,
)

data class TransferOutRequest(
    val contractId: UUID? = null,
    val receivingProviderId: String? = null,
    val receivingProviderName: String? = null,
    val receivingContractNumber: String? = null,
    val scaChallengeId: String? = null,
)

data class TransferConsentRequest(val scaChallengeId: String? = null)

data class CounterpartyResponseRequest(val accepted: Boolean? = null, val reason: String? = null)

data class IncentiveHistoryDto(val incentiveId: String? = null, val year: Int? = null, val amount: BigDecimal? = null)

data class FundsReceivedRequest(
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val originalStartDate: LocalDate? = null,
    val incentiveHistory: List<IncentiveHistoryDto?>? = null,
)

data class IncentiveHistoryResponse(val incentiveId: String, val year: Int, val amount: BigDecimal)

data class TransferResponse(
    val transferId: UUID,
    val direction: TransferDirection,
    val origin: TransferOrigin,
    val contractId: UUID,
    val counterpartyProviderId: String,
    val counterpartyProviderName: String,
    val counterpartyContractNumber: String,
    val status: String,
    val deadline: LocalDate,
    val grossAmount: BigDecimal?,
    val fee: BigDecimal?,
    val netAmount: BigDecimal?,
    val currency: String,
    val originalStartDate: LocalDate?,
    val incentiveHistory: List<IncentiveHistoryResponse>,
    val failureReason: String?,
    val compensation: Compensation,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(t: TransferRequest) = TransferResponse(
            transferId = t.id,
            direction = t.direction,
            origin = t.origin,
            contractId = t.contractId,
            counterpartyProviderId = t.counterparty.providerId,
            counterpartyProviderName = t.counterparty.providerName,
            counterpartyContractNumber = t.counterparty.contractNumber,
            status = t.status.name,
            deadline = t.deadline,
            grossAmount = t.grossAmount,
            fee = t.fee,
            netAmount = t.netAmount,
            currency = t.currency,
            originalStartDate = t.originalStartDate,
            incentiveHistory = t.incentiveHistory.map { IncentiveHistoryResponse(it.incentiveId, it.year, it.amount) },
            failureReason = t.failureReason,
            compensation = t.compensation,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
        )
    }
}
