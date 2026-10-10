// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.exit.Claimant
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.domain.exit.ExitCalculator
import com.openbank.pension.domain.exit.TerminationStatus
import com.openbank.pension.domain.exit.exitRules
import com.openbank.pension.domain.model.ContractStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class NotifyDeathCommand(
    val operator: String,
    val contractId: UUID,
    val dateOfDeath: LocalDate,
    val evidenceRef: String,
    val idempotencyKey: String,
)

data class ClaimantDesignation(val name: String, val partyId: UUID?, val sharePercent: BigDecimal, val estate: Boolean)

/**
 * Death of the participant (ADR-0334 §4 step 8), driven by operators: registration with evidence
 * freezes the contract (TERMINATING, so no contribution, strategy change or other exit proceeds),
 * each claimant passes a KYC-light check, a SECOND operator approves, and the workflow pays every
 * claimant their share and closes the contract.
 */
class DeathClaimService(
    private val contractsUseCase: PensionContractUseCase,
    private val ctx: ExitContext,
    private val launcher: ExitWorkflowLauncher,
) {
    private val stores get() = ctx.stores

    suspend fun notify(command: NotifyDeathCommand): DeathClaim {
        val contract = contractsUseCase.get(Caller.STAFF, command.contractId)
        stores.claims.findByContract(contract.id)?.let { existing ->
            check(existing.idempotencyKey == command.idempotencyKey) { "a death claim is already registered" }
            return existing
        }
        check(!contract.status.terminal) { "the contract is already ${contract.status}" }
        check(contract.status != ContractStatus.DRAFT && contract.status != ContractStatus.PENDING_ACTIVATION) {
            "a contract that never started is closed, not settled on death"
        }
        val today = LocalDate.now(ctx.clock)
        require(!command.dateOfDeath.isAfter(today)) { "dateOfDeath cannot be in the future" }
        require(command.evidenceRef.isNotBlank() && command.evidenceRef.length <= MAX_REF) { "evidenceRef is required" }
        val rules = ctx.packs.pinnedFor(contract).exitRules()
        val now = ctx.clock.instant()
        val claim = stores.claims.save(
            DeathClaim.notify(
                contract.id,
                command.dateOfDeath,
                command.evidenceRef,
                command.operator,
                DeathClaim.claimantsFrom(contract.beneficiaries, rules.death),
                command.idempotencyKey,
                now,
            ),
        )
        // #12376: a designation committed between our read and the claim insert (the insert waits
        // on the contract row lock) must not be lost. Once the claim exists no further designation
        // can commit, so the designation re-read NOW is final; take the claimants from it.
        val fresh = contractsUseCase.get(Caller.STAFF, contract.id)
        val claimed = if (fresh.beneficiaries == contract.beneficiaries) {
            claim
        } else {
            stores.claims.save(claim.replaceClaimants(DeathClaim.claimantsFrom(fresh.beneficiaries, rules.death), now))
        }
        // Freeze: a pending early-termination notice is superseded; the claim settles the contract.
        stores.notices.findOpenByContract(contract.id)
            .filter { it.status == TerminationStatus.SIGNED }
            .forEach { stores.notices.save(it.supersede(now)) }
        if (fresh.status != ContractStatus.TERMINATING) stores.contracts.save(fresh.requestTermination(now))
        return claimed
    }

    suspend fun replaceClaimants(claimId: UUID, designations: List<ClaimantDesignation>): DeathClaim {
        require(designations.size in 1..MAX_CLAIMANTS) { "1..$MAX_CLAIMANTS claimants" }
        val claim = load(claimId)
        val claimants = designations.map {
            Claimant(Ids.newId(), it.name, it.partyId, it.sharePercent, it.estate)
        }
        return stores.claims.save(claim.replaceClaimants(claimants, ctx.clock.instant()))
    }

    suspend fun verifyClaimant(operator: String, claimId: UUID, claimantId: UUID, kyc: ClaimantKyc): DeathClaim {
        val claim = load(claimId)
        val claimant = claim.claimant(claimantId)
        require(kyc.name.equals(claimant.name, ignoreCase = true)) { "the identity document names a different person" }
        val iban = IbanRule.normalise(kyc.iban)
        val verified = ctx.gateways.beneficiaryKyc.verify(kyc.copy(iban = iban, partyId = claimant.partyId))
        return stores.claims.save(claim.recordVerification(claimantId, verified, iban, operator, ctx.clock.instant()))
    }

    suspend fun approve(operator: String, claimId: UUID): DeathClaim {
        val claim = load(claimId)
        if (claim.status != DeathClaimStatus.NOTIFIED) {
            // An approval replay: the claim is already approved; make sure the settlement runs.
            check(claim.approvedBy == operator) { "the claim is already ${claim.status}" }
            if (claim.status != DeathClaimStatus.SETTLED) launcher.startDeathSettlement(claim.id)
            return claim
        }
        val contract = contractsUseCase.get(Caller.STAFF, claim.contractId)
        val pack = ctx.packs.pinnedFor(contract)
        val rules = pack.exitRules()
        val value = ctx.gateways.fund.valuation(contract.id, contract.schedule.currency).amount
        val balance = ctx.gateways.incentives.balance(contract.id, LocalDate.now(ctx.clock))
        val returned = if (rules.death.clawbackOnDeath) balance.stateIncentivesToReturn else BigDecimal.ZERO
        val approved = claim.approve(
            operator,
            value,
            returned,
            { gross -> ExitCalculator.beneficiaryQuote(pack, value, gross, balance).taxWithheld },
            ctx.clock.instant(),
        )
        stores.claims.save(approved)
        launcher.startDeathSettlement(approved.id)
        return approved
    }

    suspend fun get(claimId: UUID): DeathClaim = load(claimId)

    /** Operator queue (ADR-0334 S8): newest first, optionally by status. */
    suspend fun list(status: DeathClaimStatus?, limit: Int): List<DeathClaim> =
        stores.claims.list(status, limit.coerceIn(1, MAX_LIST))

    suspend fun findByContract(contractId: UUID): DeathClaim {
        contractsUseCase.get(Caller.STAFF, contractId)
        return stores.claims.findByContract(contractId) ?: throw ExitNotFoundException("no death claim for $contractId")
    }

    private suspend fun load(claimId: UUID): DeathClaim =
        stores.claims.findById(claimId) ?: throw ExitNotFoundException("death claim $claimId not found")

    private companion object {
        const val MAX_REF = 256
        const val MAX_CLAIMANTS = 10
    }
}
