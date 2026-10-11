// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.domain.model.Beneficiary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ClaimantVerification { PENDING, VERIFIED, REJECTED }

/**
 * One person (or the estate) entitled to a share of a deceased participant's contract. A claimant
 * is paid only after their own KYC-light verification, to an account verified in that step.
 */
data class Claimant(
    val id: UUID,
    val name: String,
    val partyId: UUID?,
    val sharePercent: BigDecimal,
    val estate: Boolean,
    val verification: ClaimantVerification = ClaimantVerification.PENDING,
    val iban: String? = null,
    val verifiedBy: String? = null,
    val gross: BigDecimal? = null,
    val tax: BigDecimal? = null,
    val net: BigDecimal? = null,
    val paymentRef: String? = null,
) {
    init {
        require(name.isNotBlank()) { "claimant name must not be blank" }
        require(sharePercent.signum() > 0 && sharePercent <= HUNDRED) { "claimant share must be in (0, 100]" }
    }

    private companion object {
        val HUNDRED = BigDecimal("100")
    }
}

enum class DeathClaimStatus {
    NOTIFIED,
    APPROVED,
    IN_PAYMENT,
    SETTLED,
    ;

    fun canMoveTo(target: DeathClaimStatus): Boolean = target in EDGES.getValue(this)

    private companion object {
        val EDGES: Map<DeathClaimStatus, Set<DeathClaimStatus>> = mapOf(
            NOTIFIED to setOf(APPROVED),
            APPROVED to setOf(IN_PAYMENT),
            IN_PAYMENT to setOf(SETTLED),
            SETTLED to emptySet(),
        )
    }
}

/**
 * Settlement of a contract on the participant's death (ADR-0334 §4 step 8). Registered by an
 * operator with evidence, which freezes the contract; claimants come from the participant's
 * designations or — where there are none and the pack allows it — the estate. Shares must total
 * exactly 100 %. Approval is four-eyes: never by the operator who registered the death.
 */
data class DeathClaim(
    val id: UUID,
    val contractId: UUID,
    val status: DeathClaimStatus,
    val dateOfDeath: LocalDate,
    val evidenceRef: String,
    val notifiedBy: String,
    val claimants: List<Claimant>,
    val idempotencyKey: String,
    val approvedBy: String? = null,
    val valuation: BigDecimal? = null,
    /** State incentives returned before distribution, when the pack claws back on death. */
    val incentiveReturn: BigDecimal = BigDecimal.ZERO,
    val redeemedAmount: BigDecimal? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Optimistic-lock version of the stored row (set on load, checked on save; ADR-0334 S8). */
    val version: Int = 0,
) {
    init {
        require(evidenceRef.isNotBlank()) { "death evidence reference is required" }
        require(claimants.isNotEmpty()) { "a death claim has at least one claimant" }
        val total = claimants.fold(BigDecimal.ZERO) { acc, c -> acc + c.sharePercent }
        require(total.compareTo(HUNDRED) == 0) { "claimant shares must total 100, were $total" }
        require(claimants.map { it.id }.toSet().size == claimants.size) { "claimant ids must be unique" }
    }

    val allVerified: Boolean get() = claimants.all { it.verification == ClaimantVerification.VERIFIED }
    val allPaid: Boolean get() = claimants.all { it.paymentRef != null }

    fun claimant(claimantId: UUID): Claimant = claimants.firstOrNull { it.id == claimantId }
        ?: throw IllegalArgumentException("claimant $claimantId not found")

    fun replaceClaimants(designations: List<Claimant>, now: Instant): DeathClaim {
        check(status == DeathClaimStatus.NOTIFIED) { "claimants can change only while NOTIFIED, was $status" }
        return copy(claimants = designations, updatedAt = now)
    }

    fun recordVerification(
        claimantId: UUID,
        verified: Boolean,
        iban: String,
        operator: String,
        now: Instant,
    ): DeathClaim {
        check(status == DeathClaimStatus.NOTIFIED) { "claimants are verified while NOTIFIED, was $status" }
        claimant(claimantId)
        val outcome = if (verified) ClaimantVerification.VERIFIED else ClaimantVerification.REJECTED
        return copy(
            claimants = claimants.map {
                if (it.id ==
                    claimantId
                ) {
                    it.copy(verification = outcome, iban = iban.takeIf { verified }, verifiedBy = operator)
                } else {
                    it
                }
            },
            updatedAt = now,
        )
    }

    /**
     * Fixes every claimant's amounts from the valuation: what remains after [incentiveReturn] is split
     * by share via [ExitMoney.split] (sums exactly), tax per the pack's beneficiary treatment.
     */
    fun approve(
        operator: String,
        valuation: BigDecimal,
        incentiveReturn: BigDecimal,
        taxes: (BigDecimal) -> BigDecimal,
        now: Instant,
    ): DeathClaim {
        check(operator != notifiedBy) { "four-eyes: the operator who registered the death cannot approve the claim" }
        check(allVerified) { "every claimant must be verified before approval" }
        val returned = ExitMoney.round(incentiveReturn.min(valuation))
        val distributable = ExitMoney.round(valuation) - returned
        check(distributable.signum() > 0) { "nothing to distribute: the contract holds no value" }
        val gross = ExitMoney.split(distributable, claimants.map { it.sharePercent })
        val priced = claimants.zip(gross).map { (c, g) ->
            val t = ExitMoney.round(taxes(g))
            c.copy(gross = g, tax = t, net = g - t)
        }
        return moveTo(DeathClaimStatus.APPROVED, now).copy(
            approvedBy = operator,
            valuation = ExitMoney.round(valuation),
            incentiveReturn = returned,
            claimants = priced,
        )
    }

    fun markRedeemed(proceeds: BigDecimal, now: Instant): DeathClaim = if (status ==
        DeathClaimStatus.IN_PAYMENT
    ) {
        this
    } else {
        moveTo(DeathClaimStatus.IN_PAYMENT, now).copy(redeemedAmount = ExitMoney.round(proceeds))
    }

    fun markClaimantPaid(claimantId: UUID, ref: String, now: Instant): DeathClaim {
        check(status == DeathClaimStatus.IN_PAYMENT) { "claimants are paid IN_PAYMENT, was $status" }
        claimant(claimantId)
        return copy(
            claimants = claimants.map {
                if (it.id == claimantId &&
                    it.paymentRef == null
                ) {
                    it.copy(paymentRef = ref)
                } else {
                    it
                }
            },
            updatedAt = now,
        )
    }

    fun settle(now: Instant): DeathClaim {
        check(allPaid) { "every claimant must be paid before the claim settles" }
        return moveTo(DeathClaimStatus.SETTLED, now)
    }

    private fun moveTo(target: DeathClaimStatus, now: Instant): DeathClaim {
        check(status.canMoveTo(target)) { "death claim $status -> $target is not allowed" }
        return copy(status = target, updatedAt = now)
    }

    companion object {
        private val HUNDRED = BigDecimal("100")

        /** Claimants from the contract's designations, or the estate when there are none and the pack allows it. */
        fun claimantsFrom(beneficiaries: List<Beneficiary>, rules: DeathRules): List<Claimant> = when {
            beneficiaries.isNotEmpty() -> beneficiaries.map {
                Claimant(Ids.newId(), it.name, it.partyId, it.sharePercent, estate = false)
            }
            rules.estateWhenNoBeneficiary -> listOf(Claimant(Ids.newId(), "Estate", null, HUNDRED, estate = true))
            else -> error("no designated beneficiary and the pack does not settle to the estate")
        }

        @Suppress("LongParameterList")
        fun notify(
            contractId: UUID,
            dateOfDeath: LocalDate,
            evidenceRef: String,
            operator: String,
            claimants: List<Claimant>,
            idempotencyKey: String,
            now: Instant,
        ) = DeathClaim(
            id = Ids.newId(),
            contractId = contractId,
            status = DeathClaimStatus.NOTIFIED,
            dateOfDeath = dateOfDeath,
            evidenceRef = evidenceRef,
            notifiedBy = operator,
            claimants = claimants,
            idempotencyKey = idempotencyKey,
            createdAt = now,
            updatedAt = now,
        )
    }
}
