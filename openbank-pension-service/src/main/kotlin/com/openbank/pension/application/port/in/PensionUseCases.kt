// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.`in`

import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.IncentivePeriod
import com.openbank.pension.domain.pack.IncentiveResult
import com.openbank.pension.domain.pack.ProviderType
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
    val idempotencyKey: String? = null,
)

/**
 * Who is acting. A customer call (through the edge) carries the participant party and may touch
 * only that party's contracts; a staff call carries none and is limited to reads by policy.
 * A contract that belongs to someone else is reported as NOT FOUND, never as forbidden, so
 * contract ids cannot be enumerated.
 */
data class Caller(val customerPartyId: UUID?) {
    /** Every change acts for a participant: a staff caller never mutates a contract. */
    fun requireParticipant() = require(customerPartyId != null) { "a contract change must be made for the participant" }

    companion object {
        fun customer(partyId: UUID) = Caller(partyId)
        val STAFF = Caller(null)
    }
}

/**
 * A strategy change of an existing contract. SCA-bound to [ScaOperation.STRATEGY_CHANGE] over
 * [StrategyChangeDocument.hash]; repeating an already-applied change is an idempotent no-op.
 */
data class ElectStrategyCommand(
    val caller: Caller,
    val contractId: UUID,
    val strategyCode: String,
    val effectiveFrom: LocalDate?,
    val scaChallengeId: String?,
    val acknowledgedWarnings: Set<com.openbank.pension.domain.questionnaire.WarningCode> = emptySet(),
    val language: String? = null,
)

/** The document a strategy-change challenge signs: contract, strategy, effective date, warnings acknowledged. */
object StrategyChangeDocument {
    fun hash(
        contractId: UUID,
        strategyCode: String,
        effectiveFrom: LocalDate,
        acknowledged: Set<com.openbank.pension.domain.questionnaire.WarningCode>,
    ): String {
        val doc = listOf(
            "pension-strategy-change",
            contractId,
            strategyCode,
            effectiveFrom,
            acknowledged.map { it.name }.sorted().joinToString(","),
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(doc.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class IncentiveEvaluationCommand(
    val caller: Caller,
    val contractId: UUID,
    val contribution: BigDecimal,
    val period: IncentivePeriod,
    val employerContributionAnnual: BigDecimal,
    val sharedCapUsed: Map<String, BigDecimal>,
)

interface PensionContractUseCase {
    suspend fun createDraft(command: CreateDraftCommand): PensionContract
    suspend fun submit(caller: Caller, id: UUID): PensionContract
    suspend fun electStrategy(command: ElectStrategyCommand): PensionContract
    suspend fun suspendContributions(caller: Caller, id: UUID): PensionContract
    suspend fun resumeContributions(caller: Caller, id: UUID): PensionContract
    suspend fun get(caller: Caller, id: UUID): PensionContract

    /** A participant sees exactly their own contracts; staff list by status. */
    suspend fun list(caller: Caller, status: ContractStatus?, limit: Int): List<PensionContract>
    suspend fun evaluateIncentives(command: IncentiveEvaluationCommand): List<IncentiveResult>
}
