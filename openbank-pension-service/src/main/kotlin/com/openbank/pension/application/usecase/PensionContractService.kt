// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.ContractVisibility
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.ElectStrategyCommand
import com.openbank.pension.application.port.`in`.IncentiveEvaluationCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.`in`.StrategyChangeDocument
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.port.out.StrategySuitabilityPort
import com.openbank.pension.application.port.out.StrategySuitabilityRequest
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.IncentiveResult
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.pack.PackEvaluator
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
    private val notifier: ParticipantNotifier,
    /** The one suitability gate for every strategy a contract holds (#12384). */
    private val suitability: StrategySuitabilityPort,
    /** Document-bound SCA for a strategy change (ADR-0335 `pension-strategy-change:` namespace). */
    private val sca: ScaVerificationPort,
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
        // A draft opened outside onboarding has no suitability assessment: the same gate as every
        // later change decides which strategy it may start with (the most conservative only).
        suitability.authorize(
            StrategySuitabilityRequest(
                contractId = null,
                jurisdiction = pack.jurisdiction,
                productLine = command.productLine,
                packVersion = pack.version,
                strategyCode = command.initialStrategy,
                acknowledged = emptySet(),
                language = null,
            ),
        )
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

    override suspend fun list(caller: Caller, status: ContractStatus?, limit: Int): List<PensionContract> {
        val page = limit.coerceIn(1, MAX_LIST)
        val party = caller.customerPartyId ?: return contracts.findByStatus(status, page)
        // The participant's own rows only; the status filter narrows, it never widens.
        return contracts.findByParticipant(party, page).filter { status == null || it.status == status }
    }

    /**
     * The ONE path that changes a contract's strategy. In order: ownership (someone else's contract
     * is 404), idempotent replay (the same change already applied answers the contract unchanged,
     * nothing is signed or sent twice), the suitability gate (current assessment, regime,
     * acknowledged warnings), then the single-use SCA challenge over the exact change document.
     */
    override suspend fun electStrategy(command: ElectStrategyCommand): PensionContract {
        command.caller.requireParticipant()
        val today = LocalDate.now(clock)
        val from = command.effectiveFrom ?: today
        require(!from.isBefore(today)) { "a strategy change cannot take effect in the past" }
        val contract = get(command.caller, command.contractId)
        val current = contract.currentStrategy
        if (current.strategyCode == command.strategyCode && current.effectiveFrom == from) return contract
        val approval = suitability.authorize(
            StrategySuitabilityRequest(
                contractId = contract.id,
                jurisdiction = contract.jurisdiction,
                productLine = contract.productLine,
                packVersion = contract.packVersion,
                strategyCode = command.strategyCode,
                acknowledged = command.acknowledgedWarnings,
                language = command.language,
            ),
        )
        val challenge = command.scaChallengeId?.takeIf { it.isNotBlank() }
        val signed = challenge != null &&
            sca.verify(
                contract.participantPartyId,
                challenge,
                StrategyChangeDocument.hash(contract.id, command.strategyCode, from, command.acknowledgedWarnings),
                ScaOperation.STRATEGY_CHANGE,
            )
        if (!signed) throw StrategyChangeScaFailedException()
        val saved = transition(command.caller, command.contractId, null) {
            it.electStrategy(command.strategyCode, from, clock.instant())
        }
        suitability.record(approval)
        // #12379: the participant is told when the new strategy takes effect, after it is stored.
        ParticipantNotices.send(
            notifier,
            ParticipantNotices.strategyChange(saved.participantPartyId, saved.id, command.strategyCode, from),
        )
        return saved
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

private const val MAX_LIST = 100

/** 403: the strategy change was not signed by a verified single-use challenge over its document. */
class StrategyChangeScaFailedException :
    RuntimeException("strong customer authentication failed for this strategy change")
