// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.model

import com.openbank.pension.domain.pack.ProviderType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The two product lines ADR-0334 §2a ships from the start; the difference between them is pack data. */
enum class ProductLine { DPS, DIP }

enum class PayoutForm { LUMP_SUM, ANNUITY, PHASED_WITHDRAWAL, EARLY_WITHDRAWAL, SURRENDER }

enum class ContributionFrequency { MONTHLY, QUARTERLY, ANNUALLY }

/**
 * Contract lifecycle (ADR-0334 §1). Every edge is listed here and nowhere else; the aggregate asks
 * [canMoveTo] before every change, so an unlisted transition is unrepresentable rather than merely
 * untested. The three terminal states have no outgoing edge.
 */
enum class ContractStatus {
    DRAFT,
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    TERMINATING,
    PAID_OUT,
    TRANSFERRED_OUT,
    CLOSED,
    ;

    fun canMoveTo(target: ContractStatus): Boolean = target in TRANSITIONS.getValue(this)

    val terminal: Boolean get() = TRANSITIONS.getValue(this).isEmpty()

    private companion object {
        val TRANSITIONS: Map<ContractStatus, Set<ContractStatus>> = mapOf(
            DRAFT to setOf(PENDING_ACTIVATION, CLOSED),
            PENDING_ACTIVATION to setOf(ACTIVE, CLOSED),
            ACTIVE to setOf(SUSPENDED, TERMINATING),
            SUSPENDED to setOf(ACTIVE, TERMINATING),
            TERMINATING to setOf(PAID_OUT, TRANSFERRED_OUT, CLOSED),
            PAID_OUT to emptySet(),
            TRANSFERRED_OUT to emptySet(),
            CLOSED to emptySet(),
        )
    }
}

data class StrategyElection(
    val strategyCode: String,
    val effectiveFrom: LocalDate,
    val electedAt: Instant,
) {
    init {
        require(strategyCode.isNotBlank()) { "strategyCode must not be blank" }
    }
}

data class ContributionSchedule(
    val amount: BigDecimal,
    val currency: String,
    val frequency: ContributionFrequency,
    val employerAmount: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(amount.signum() >= 0) { "contribution amount must not be negative" }
        require(employerAmount.signum() >= 0) { "employer contribution must not be negative" }
        require(currency.length == ISO_CURRENCY_LENGTH) { "currency must be an ISO 4217 code" }
    }

    private companion object {
        const val ISO_CURRENCY_LENGTH = 3
    }
}

data class Beneficiary(
    val name: String,
    val partyId: UUID? = null,
    val sharePercent: BigDecimal,
) {
    init {
        require(name.isNotBlank()) { "beneficiary name must not be blank" }
        require(sharePercent.signum() > 0 && sharePercent <= HUNDRED) { "beneficiary share must be in (0, 100]" }
    }

    companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")
    }
}

/**
 * The participant-side pension contract (ADR-0334 §1). Immutable: every behaviour returns a new
 * instance, and every lifecycle change goes through [moveTo], which refuses an edge
 * [ContractStatus] does not list with `IllegalStateException` (a 409 at the REST edge).
 *
 * The jurisdiction pack is PINNED by `(jurisdiction, productLine, packVersion)` at creation and
 * never re-resolved: a contract is judged by the law it was sold under (ADR-0212 D3).
 */
data class PensionContract(
    val id: UUID,
    val participantPartyId: UUID,
    val productLine: ProductLine,
    val jurisdiction: String,
    val packVersion: Int,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val participantBirthDate: LocalDate,
    val status: ContractStatus,
    val schedule: ContributionSchedule,
    val strategyHistory: List<StrategyElection>,
    val beneficiaries: List<Beneficiary>,
    val startDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(strategyHistory.isNotEmpty()) { "a contract always carries an elected strategy" }
        val total = beneficiaries.fold(BigDecimal.ZERO) { acc, b -> acc + b.sharePercent }
        require(beneficiaries.isEmpty() || total.compareTo(Beneficiary.HUNDRED) == 0) {
            "beneficiary shares must total 100"
        }
        require(status == ContractStatus.DRAFT || status == ContractStatus.PENDING_ACTIVATION || startDate != null) {
            "an activated contract carries its start date"
        }
    }

    /** The election in force today: the newest by effective date, then by election time. */
    val currentStrategy: StrategyElection
        get() = strategyHistory.maxWith(compareBy<StrategyElection>({ it.effectiveFrom }, { it.electedAt }))

    fun submit(now: Instant): PensionContract = moveTo(ContractStatus.PENDING_ACTIVATION, now)

    fun activate(startDate: LocalDate, now: Instant): PensionContract {
        check(status.canMoveTo(ContractStatus.ACTIVE) && status == ContractStatus.PENDING_ACTIVATION) {
            "transition $status -> ${ContractStatus.ACTIVE} is not allowed"
        }
        return copy(status = ContractStatus.ACTIVE, startDate = startDate, updatedAt = now)
    }

    fun suspendContributions(now: Instant): PensionContract = moveTo(ContractStatus.SUSPENDED, now)

    fun resumeContributions(now: Instant): PensionContract {
        check(status == ContractStatus.SUSPENDED) { "only a SUSPENDED contract can resume, was $status" }
        return moveTo(ContractStatus.ACTIVE, now)
    }

    fun requestTermination(now: Instant): PensionContract = moveTo(ContractStatus.TERMINATING, now)

    fun close(now: Instant): PensionContract = moveTo(ContractStatus.CLOSED, now)

    /** TERMINATING -> TRANSFERRED_OUT once a transfer-out settled (ADR-0334 slice S2). */
    fun markTransferredOut(now: Instant): PensionContract = moveTo(ContractStatus.TRANSFERRED_OUT, now)

    /** Strategy can change in any non-terminal state; the previous elections stay as history. */
    fun electStrategy(strategyCode: String, effectiveFrom: LocalDate, now: Instant): PensionContract {
        check(!status.terminal && status != ContractStatus.TERMINATING) {
            "strategy cannot change on a $status contract"
        }
        val election = StrategyElection(strategyCode, effectiveFrom, now)
        return copy(strategyHistory = strategyHistory + election, updatedAt = now)
    }

    fun designateBeneficiaries(designations: List<Beneficiary>, now: Instant): PensionContract {
        check(!status.terminal) { "beneficiaries cannot change on a $status contract" }
        return copy(beneficiaries = designations, updatedAt = now)
    }

    private fun moveTo(target: ContractStatus, now: Instant): PensionContract {
        check(status.canMoveTo(target)) { "transition $status -> $target is not allowed" }
        return copy(status = target, updatedAt = now)
    }

    companion object {
        @Suppress("LongParameterList")
        fun draft(
            participantPartyId: UUID,
            productLine: ProductLine,
            jurisdiction: String,
            packVersion: Int,
            providerEntityId: UUID,
            providerType: ProviderType,
            participantBirthDate: LocalDate,
            schedule: ContributionSchedule,
            initialStrategy: String,
            beneficiaries: List<Beneficiary>,
            today: LocalDate,
            now: Instant,
        ): PensionContract = PensionContract(
            id = UUID.randomUUID(),
            participantPartyId = participantPartyId,
            productLine = productLine,
            jurisdiction = jurisdiction,
            packVersion = packVersion,
            providerEntityId = providerEntityId,
            providerType = providerType,
            participantBirthDate = participantBirthDate,
            status = ContractStatus.DRAFT,
            schedule = schedule,
            strategyHistory = listOf(StrategyElection(initialStrategy, today, now)),
            beneficiaries = beneficiaries,
            startDate = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
