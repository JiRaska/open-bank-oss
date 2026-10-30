// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.maintenance

import com.openbank.pension.application.exit.DeathClaimRepository
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationHistory
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationVersion
import com.openbank.pension.domain.maintenance.ContributionScheduleHistory
import com.openbank.pension.domain.maintenance.PlannedBeneficiaryChange
import com.openbank.pension.domain.maintenance.PlannedScheduleChange
import com.openbank.pension.domain.maintenance.ScheduleChangeRequest
import com.openbank.pension.domain.maintenance.ScheduleVersion
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Persistence of the two change histories (#12376). */
interface ContractChangeStore {
    suspend fun scheduleHistory(contractId: UUID): ContributionScheduleHistory

    /** Inserts the new versions and the SUPERSEDED status of earlier ones; a lost race is a 409. */
    suspend fun saveSchedule(history: ContributionScheduleHistory): ContributionScheduleHistory

    suspend fun beneficiaryHistory(contractId: UUID): BeneficiaryDesignationHistory

    /**
     * In ONE transaction: the contract row's designation (optimistic lock on [contract]'s version),
     * the appended history row, and a re-check that no death claim was registered meanwhile.
     */
    suspend fun saveBeneficiaries(contract: PensionContract, version: BeneficiaryDesignationVersion)
}

/** The SCA challenge did not verify for this exact document — 403, never a hint which part failed. */
class ChangeNotAuthorisedException(message: String) : RuntimeException(message)

data class ScheduleView(
    val contract: PensionContract,
    val history: ContributionScheduleHistory,
    val inForce: ScheduleVersion?,
    val pending: ScheduleVersion?,
)

data class ChangeScheduleCommand(
    val caller: Caller,
    val contractId: UUID,
    val request: ScheduleChangeRequest,
    val scaChallengeId: String,
    val idempotencyKey: String,
)

data class ChangeBeneficiariesCommand(
    val caller: Caller,
    val contractId: UUID,
    val beneficiaries: List<Beneficiary>,
    val scaChallengeId: String,
    val idempotencyKey: String,
)

/**
 * Contribution-schedule and beneficiary changes on an existing contract (ADR-0334, #12376).
 * Orchestration only — every invariant is the history aggregate's. Shape of each change, same as
 * the exit quote/confirm pair: `preview*` validates and returns the document hash to sign;
 * `change*` re-plans on the CURRENT state, verifies the SCA challenge over that hash (fails closed
 * when sca-service is not wired), then stores. A retry with the same `Idempotency-Key` returns the
 * version it created instead of spending a second challenge.
 */
class ContractMaintenanceService(
    private val contracts: PensionContractUseCase,
    private val store: ContractChangeStore,
    private val deathClaims: DeathClaimRepository,
    private val sca: ScaVerificationPort,
    private val packs: JurisdictionPackRegistry,
    private val clock: Clock,
) {

    suspend fun previewSchedule(
        caller: Caller,
        contractId: UUID,
        request: ScheduleChangeRequest,
    ): PlannedScheduleChange {
        caller.requireParticipant()
        val contract = contracts.get(caller, contractId)
        return store.scheduleHistory(contractId).plan(contract, packs.pinnedFor(contract), request, today())
    }

    suspend fun changeSchedule(command: ChangeScheduleCommand): ScheduleVersion {
        command.caller.requireParticipant()
        val contract = contracts.get(command.caller, command.contractId)
        val history = store.scheduleHistory(contract.id)
        history.byIdempotencyKey(command.idempotencyKey)?.let { return it }
        val today = today()
        val plan = history.plan(contract, packs.pinnedFor(contract), command.request, today)
        if (!sca.verify(
                contract.participantPartyId,
                command.scaChallengeId,
                plan.documentSha256,
                ScaOperation.SCHEDULE_CHANGE,
            )
        ) {
            throw ChangeNotAuthorisedException("strong customer authentication failed for this change")
        }
        val saved = store.saveSchedule(
            history.record(plan, command.scaChallengeId, command.idempotencyKey, today, clock.instant()),
        )
        return saved.versions.last()
    }

    suspend fun schedule(caller: Caller, contractId: UUID): ScheduleView {
        val contract = contracts.get(caller, contractId)
        val history = store.scheduleHistory(contract.id)
        val today = today()
        return ScheduleView(contract, history, history.inForceOn(today), history.pendingAfter(today))
    }

    suspend fun previewBeneficiaries(
        caller: Caller,
        contractId: UUID,
        beneficiaries: List<Beneficiary>,
    ): PlannedBeneficiaryChange {
        caller.requireParticipant()
        val contract = contracts.get(caller, contractId)
        return store.beneficiaryHistory(contractId).plan(contract, beneficiaries, deathClaimed(contract))
    }

    suspend fun changeBeneficiaries(command: ChangeBeneficiariesCommand): BeneficiaryDesignationVersion {
        command.caller.requireParticipant()
        val contract = contracts.get(command.caller, command.contractId)
        val history = store.beneficiaryHistory(contract.id)
        history.byIdempotencyKey(command.idempotencyKey)?.let { return it }
        val claimed = deathClaimed(contract)
        val plan = history.plan(contract, command.beneficiaries, claimed)
        if (!sca.verify(
                contract.participantPartyId,
                command.scaChallengeId,
                plan.documentSha256,
                ScaOperation.BENEFICIARY_CHANGE,
            )
        ) {
            throw ChangeNotAuthorisedException("strong customer authentication failed for this change")
        }
        val now = clock.instant()
        val version = history.record(plan, claimed, command.scaChallengeId, command.idempotencyKey, now).versions.last()
        store.saveBeneficiaries(contract.designateBeneficiaries(plan.beneficiaries, now), version)
        return version
    }

    suspend fun beneficiaries(caller: Caller, contractId: UUID): Pair<PensionContract, BeneficiaryDesignationHistory> {
        val contract = contracts.get(caller, contractId)
        return contract to store.beneficiaryHistory(contract.id)
    }

    private suspend fun deathClaimed(contract: PensionContract) = deathClaims.findByContract(contract.id) != null

    private fun today() = LocalDate.now(clock)
}
