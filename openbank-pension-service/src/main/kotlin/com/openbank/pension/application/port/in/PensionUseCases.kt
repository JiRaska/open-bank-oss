// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.`in`

import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveResult
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.pack.SurrenderInputs
import com.openbank.pension.domain.pack.SurrenderPreview
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CreateDraftCommand(
    val participantPartyId: UUID,
    val productLine: ProductLine,
    val jurisdiction: String,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val birthDate: LocalDate,
    val residencyCountry: String?,
    val residencyEvidence: Set<String>,
    val hasGuardian: Boolean,
    val schedule: ContributionSchedule,
    val initialStrategy: String,
    val beneficiaries: List<Beneficiary>,
)

data class IncentiveEvaluationCommand(
    val contractId: UUID,
    val contribution: BigDecimal,
    val period: IncentivePeriod,
    val employerContributionAnnual: BigDecimal,
    val sharedCapUsed: Map<String, BigDecimal>,
)

data class EarlyTerminationCommand(val contractId: UUID, val inputs: SurrenderInputs, val confirm: Boolean)

data class EarlyTerminationResult(val contract: PensionContract, val preview: SurrenderPreview)

interface PensionContractUseCase {
    suspend fun createDraft(command: CreateDraftCommand): PensionContract
    suspend fun submit(id: UUID): PensionContract
    suspend fun activate(id: UUID): PensionContract
    suspend fun electStrategy(id: UUID, strategyCode: String, effectiveFrom: LocalDate?): PensionContract
    suspend fun suspendContributions(id: UUID): PensionContract
    suspend fun resumeContributions(id: UUID): PensionContract
    suspend fun get(id: UUID): PensionContract
    suspend fun evaluateIncentives(command: IncentiveEvaluationCommand): List<IncentiveResult>
    suspend fun requestEarlyTermination(command: EarlyTerminationCommand): EarlyTerminationResult
}
