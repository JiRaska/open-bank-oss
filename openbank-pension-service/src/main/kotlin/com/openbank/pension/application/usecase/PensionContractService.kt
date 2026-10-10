// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.ContractVisibility
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.EarlyTerminationCommand
import com.openbank.pension.application.port.`in`.EarlyTerminationResult
import com.openbank.pension.application.port.`in`.IncentiveEvaluationCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.IncentiveResult
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
        command.idempotencyKey?.let { key ->
            contracts.findByIdempotencyKey(command.participantPartyId, key)?.let { return it }
        }
        require(command.beneficiaries.size <= Limits.MAX_BENEFICIARIES) {
            "at most ${Limits.MAX_BENEFICIARIES} beneficiaries"
        }
        require(command.residencyEvidence.size <= Limits.MAX_ENTRIES) { "too many residency evidence entries" }
        Limits.requireCode(command.jurisdiction, "jurisdiction")
        val today = LocalDate.now(clock)
        require(command.birthDate.isBefore(today)) { "birthDate must be in the past" }
        val pack = packs.resolve(command.jurisdiction, command.productLine, today)
        require(command.providerType in pack.permittedProviderTypes) {
            "provider type ${command.providerType} may not provide ${pack.productLine} under " +
                "${pack.jurisdiction} pack v${pack.version}"
        }
        require(command.schedule.currency == pack.currency) {
            "contribution currency must be ${pack.currency} under this pack"
        }
        val eligibility = PackEvaluator.checkEligibility(
            pack,
            command.birthDate,
            command.residencyCountry,
            command.residencyEvidence,
            command.hasGuardian,
            today,
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
            idempotencyKey = command.idempotencyKey,
        )
        return contracts.save(draft)
    }

    override suspend fun submit(caller: Caller, id: UUID) =
        transition(caller, id, ContractStatus.PENDING_ACTIVATION) { it.submit(clock.instant()) }

    override suspend fun activate(caller: Caller, id: UUID) =
        transition(caller, id, ContractStatus.ACTIVE) { it.activate(LocalDate.now(clock), clock.instant()) }

    override suspend fun electStrategy(caller: Caller, id: UUID, strategyCode: String, effectiveFrom: LocalDate?) =
        transition(caller, id, null) {
            val from = effectiveFrom ?: LocalDate.now(clock)
            require(!from.isBefore(LocalDate.now(clock))) { "a strategy change cannot take effect in the past" }
            it.electStrategy(strategyCode, from, clock.instant())
        }

    override suspend fun suspendContributions(caller: Caller, id: UUID) =
        transition(caller, id, ContractStatus.SUSPENDED) { it.suspendContributions(clock.instant()) }

    override suspend fun resumeContributions(caller: Caller, id: UUID) =
        transition(caller, id, ContractStatus.ACTIVE) { it.resumeContributions(clock.instant()) }

    /**
     * The ownership check. Someone else's contract is NOT FOUND — the same answer as an id that
     * does not exist — so a customer cannot learn which ids are real.
     */
    override suspend fun get(caller: Caller, id: UUID): PensionContract {
        val contract = contracts.findById(id) ?: throw ContractNotFoundException(id)
        return ContractVisibility.requireVisible(caller, contract)
    }

    override suspend fun evaluateIncentives(command: IncentiveEvaluationCommand): List<IncentiveResult> {
        val contract = get(command.caller, command.contractId)
        return PackEvaluator.evaluateIncentives(
            packs.pinnedFor(contract),
            command.contribution,
            command.period,
            command.employerContributionAnnual,
            command.sharedCapUsed,
        )
    }

    override suspend fun requestEarlyTermination(command: EarlyTerminationCommand): EarlyTerminationResult {
        val contract = get(command.caller, command.contractId)
        if (command.confirm) command.caller.requireParticipant()
        // A replayed confirmation finds the contract already TERMINATING and answers the same
        // preview without a second transition.
        val replay = command.confirm && contract.status == ContractStatus.TERMINATING
        check(replay || contract.status == ContractStatus.ACTIVE || contract.status == ContractStatus.SUSPENDED) {
            "early termination needs an ACTIVE or SUSPENDED contract, was ${contract.status}"
        }
        val preview = SurrenderCalculator.preview(
            contract,
            packs.pinnedFor(contract),
            command.inputs,
            LocalDate.now(clock),
        )
        val result = if (command.confirm &&
            !replay
        ) {
            contracts.save(contract.requestTermination(clock.instant()))
        } else {
            contract
        }
        return EarlyTerminationResult(result, preview)
    }

    /**
     * A lifecycle action is idempotent: a retry that finds the contract already in [target] returns
     * it unchanged instead of failing the second attempt of an action that succeeded. A `null`
     * target (strategy election) always applies the change.
     */
    private suspend fun transition(
        caller: Caller,
        id: UUID,
        target: ContractStatus?,
        change: (PensionContract) -> PensionContract,
    ): PensionContract {
        caller.requireParticipant()
        val current = get(caller, id)
        return if (target != null && current.status == target) current else contracts.save(change(current))
    }
}
