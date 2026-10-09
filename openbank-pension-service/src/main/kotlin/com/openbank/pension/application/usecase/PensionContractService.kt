// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.ContractVisibility
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.IncentiveEvaluationCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContractStatus
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
) : PensionContractUseCase {

    override suspend fun createDraft(command: CreateDraftCommand): PensionContract {
        error("direct contract creation is unavailable; complete the pension onboarding flow")
    }

    override suspend fun submit(caller: Caller, id: UUID): PensionContract {
        caller.requireParticipant()
        get(caller, id)
        error("direct contract submission is unavailable; complete the pension onboarding flow")
    }

    override suspend fun list(caller: Caller, status: ContractStatus?, limit: Int): List<PensionContract> {
        val page = limit.coerceIn(1, MAX_LIST)
        val party = caller.customerPartyId ?: return contracts.findByStatus(status, page)
        // The participant's own rows only; the status filter narrows, it never widens.
        return contracts.findByParticipant(party, page).filter { status == null || it.status == status }
    }

    override suspend fun electStrategy(
        caller: Caller,
        id: UUID,
        strategyCode: String,
        effectiveFrom: LocalDate?,
    ): PensionContract {
        caller.requireParticipant()
        get(caller, id)
        error("strategy changes are unavailable until questionnaire and catalog mapping checks are supported")
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
     * it unchanged instead of failing the second attempt of an action that succeeded.
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
