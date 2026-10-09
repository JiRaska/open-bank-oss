// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.EarlyTerminationCommand
import com.openbank.pension.application.port.`in`.EarlyTerminationResult
import com.openbank.pension.application.port.`in`.IncentiveEvaluationCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.IncentiveResult
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.pack.PackEvaluator
import com.openbank.pension.domain.pack.SurrenderCalculator
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Participant-side contract lifecycle (ADR-0334 §1, slice S1). Plain Kotlin: the CDI wiring lives
 * in infrastructure, so this class is unit-testable without Quarkus.
 */
class PensionContractService(
    private val contracts: PensionContractRepository,
    private val packs: JurisdictionPackRegistry,
    private val clock: Clock,
) : PensionContractUseCase {

    override suspend fun createDraft(command: CreateDraftCommand): PensionContract {
        val today = LocalDate.now(clock)
        val pack = packs.resolve(command.jurisdiction, command.productLine, today)
        require(command.providerType in pack.permittedProviderTypes) {
            "provider type ${command.providerType} may not provide ${pack.productLine} under " +
                "${pack.jurisdiction} pack v${pack.version}"
        }
        require(command.schedule.currency == pack.currency) {
            "contribution currency must be ${pack.currency} under this pack"
        }
        val eligibility = PackEvaluator.checkEligibility(
            pack, command.birthDate, command.residencyCountry, command.residencyEvidence, command.hasGuardian, today,
        )
        require(eligibility.eligible) { "participant is not eligible: ${eligibility.reasons.joinToString("; ")}" }
        val draft = PensionContract.draft(
            participantPartyId = command.participantPartyId,
            productLine = command.productLine,
            jurisdiction = pack.jurisdiction,
            packVersion = pack.version,
            providerEntityId = command.providerEntityId,
            providerType = command.providerType,
            participantBirthDate = command.birthDate,
            schedule = command.schedule,
            initialStrategy = command.initialStrategy,
            beneficiaries = command.beneficiaries,
            today = today,
            now = clock.instant(),
        )
        return contracts.save(draft)
    }

    override suspend fun submit(id: UUID) = mutate(id) { it.submit(clock.instant()) }

    override suspend fun activate(id: UUID) = mutate(id) { it.activate(LocalDate.now(clock), clock.instant()) }

    override suspend fun electStrategy(id: UUID, strategyCode: String, effectiveFrom: LocalDate?) = mutate(id) {
        val from = effectiveFrom ?: LocalDate.now(clock)
        require(!from.isBefore(LocalDate.now(clock))) { "a strategy change cannot take effect in the past" }
        it.electStrategy(strategyCode, from, clock.instant())
    }

    override suspend fun suspendContributions(id: UUID) = mutate(id) { it.suspendContributions(clock.instant()) }

    override suspend fun resumeContributions(id: UUID) = mutate(id) { it.resumeContributions(clock.instant()) }

    override suspend fun get(id: UUID): PensionContract = contracts.findById(id) ?: throw ContractNotFoundException(id)

    override suspend fun evaluateIncentives(command: IncentiveEvaluationCommand): List<IncentiveResult> {
        val contract = get(command.contractId)
        return PackEvaluator.evaluateIncentives(
            pinnedPack(contract),
            command.contribution,
            command.period,
            command.employerContributionAnnual,
            command.sharedCapUsed,
        )
    }

    override suspend fun requestEarlyTermination(command: EarlyTerminationCommand): EarlyTerminationResult {
        val contract = get(command.contractId)
        check(contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "early termination needs an ACTIVE or SUSPENDED contract, was ${contract.status}"
        }
        val preview = SurrenderCalculator.preview(contract, pinnedPack(contract), command.inputs, LocalDate.now(clock))
        val result = if (command.confirm) contracts.save(contract.requestTermination(clock.instant())) else contract
        return EarlyTerminationResult(result, preview)
    }

    private fun pinnedPack(contract: PensionContract): JurisdictionPack =
        packs.pinned(contract.jurisdiction, contract.productLine, contract.packVersion)

    private suspend fun mutate(id: UUID, change: (PensionContract) -> PensionContract): PensionContract =
        contracts.save(change(get(id)))
}
