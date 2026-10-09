// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.dto

import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveResult
import com.openbank.pension.domain.pack.ProviderType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Request fields are nullable and checked at the edge with `requireNotNull` (400 via libs-runtime):
 * a non-null Kotlin property Jackson cannot fill is a deserialisation failure, not a clear message.
 */
data class ScheduleDto(
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val frequency: ContributionFrequency? = null,
    val employerAmount: BigDecimal? = null,
)

data class BeneficiaryDto(val name: String? = null, val partyId: UUID? = null, val sharePercent: BigDecimal? = null) {
    fun toDomain(index: Int) = Beneficiary(
        name = requireNotNull(name) { "beneficiaries[$index].name is required" },
        partyId = partyId,
        sharePercent = requireNotNull(sharePercent) { "beneficiaries[$index].sharePercent is required" },
    )

    companion object {
        fun from(b: Beneficiary) = BeneficiaryDto(b.name, b.partyId, b.sharePercent)
    }
}

data class CreateContractRequest(
    val productLine: ProductLine? = null,
    val jurisdiction: String? = null,
    val providerEntityId: UUID? = null,
    val providerType: ProviderType? = null,
    val birthDate: LocalDate? = null,
    val residencyCountry: String? = null,
    val residencyEvidence: List<String?>? = null,
    val hasGuardian: Boolean? = null,
    val schedule: ScheduleDto? = null,
    val strategyCode: String? = null,
    val beneficiaries: List<BeneficiaryDto?>? = null,
)

data class ElectStrategyRequest(
    val strategyCode: String? = null,
    val effectiveFrom: LocalDate? = null,
    /** Single-use challenge signed over `pension-strategy-change:<StrategyChangeDocument.hash>`. */
    val scaChallengeId: String? = null,
    /** Warnings shown and acknowledged for this change (see 409 WARNINGS_REQUIRED). */
    val acknowledgedWarnings: List<com.openbank.pension.domain.questionnaire.WarningCode?>? = null,
    /** Language the warnings were shown in (cs / en): the acknowledgement binds that wording. */
    val language: String? = null,
)

data class IncentiveEvaluationRequest(
    val contribution: BigDecimal? = null,
    val period: IncentivePeriod? = null,
    val employerContributionAnnual: BigDecimal? = null,
    val sharedCapUsed: Map<String, BigDecimal?>? = null,
)

data class StrategyElectionDto(val strategyCode: String, val effectiveFrom: LocalDate, val electedAt: Instant)

data class ContractResponse(
    val contractId: UUID,
    val participantPartyId: UUID,
    val productLine: ProductLine,
    val jurisdiction: String,
    val packVersion: Int,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val status: String,
    val schedule: ScheduleDto,
    val currentStrategy: StrategyElectionDto,
    val strategyHistory: List<StrategyElectionDto>,
    val beneficiaries: List<BeneficiaryDto>,
    val startDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: PensionContract) = ContractResponse(
            contractId = c.id,
            participantPartyId = c.participantPartyId,
            productLine = c.productLine,
            jurisdiction = c.jurisdiction,
            packVersion = c.packVersion,
            providerEntityId = c.providerEntityId,
            providerType = c.providerType,
            status = c.status.name,
            schedule = ScheduleDto(
                c.schedule.amount,
                c.schedule.currency,
                c.schedule.frequency,
                c.schedule.employerAmount,
            ),
            currentStrategy = c.currentStrategy.let {
                StrategyElectionDto(it.strategyCode, it.effectiveFrom, it.electedAt)
            },
            strategyHistory = c.strategyHistory.map {
                StrategyElectionDto(it.strategyCode, it.effectiveFrom, it.electedAt)
            },
            beneficiaries = c.beneficiaries.map(BeneficiaryDto::from),
            startDate = c.startDate,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}

data class IncentiveResultResponse(
    val incentiveId: String,
    val type: String,
    val period: String,
    val amount: BigDecimal,
    val indicativeSaving: BigDecimal?,
    val claimChannel: String,
    val explanation: String,
) {
    companion object {
        fun from(r: IncentiveResult) = IncentiveResultResponse(
            r.incentiveId,
            r.type.name,
            r.period.name,
            r.amount,
            r.indicativeSaving,
            r.claimChannel.name,
            r.explanation,
        )
    }
}
