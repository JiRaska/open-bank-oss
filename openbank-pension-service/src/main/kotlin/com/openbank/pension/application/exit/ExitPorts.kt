// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.domain.exit.AnnuityPolicy
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.IncentiveBalance
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.TerminationNotice
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * Outbound ports of the exit slice (ADR-0334 S5). Every money-moving call carries an
 * idempotency key derived from the aggregate and the step (never random), so a Temporal retry of
 * an activity repeats the SAME instruction and the receiving side deduplicates it.
 */

/** pension-fund-service unit register (ADR-0334 §1). Clients never reach it directly. */
interface FundAdministrationPort {
    /** Value of all units held for the contract at the latest published NAV. */
    suspend fun redemptionValue(contractId: UUID): BigDecimal

    /** Sells units worth [amount]; returns the proceeds actually delivered. */
    suspend fun redeem(contractId: UUID, amount: BigDecimal, idempotencyKey: String): BigDecimal
}

/** The incentive/clawback ledger slice S3 builds; read for quotes, settled on exit. */
interface IncentiveClawbackPort {
    suspend fun balance(contractId: UUID, asOf: LocalDate): IncentiveBalance

    /** Returns [amount] of state incentives to the state (claim channel of the pack). */
    suspend fun settleClawback(contractId: UUID, amount: BigDecimal, idempotencyKey: String)
}

/** Remits tax withheld or recaptured on an exit to the tax authority. */
interface TaxWithholdingPort {
    suspend fun remit(contractId: UUID, kind: String, amount: BigDecimal, idempotencyKey: String)
}

/** account-service: is [iban] a verified account held by [partyId]? */
interface OwnAccountVerificationPort {
    suspend fun isOwnVerifiedAccount(partyId: UUID, iban: String): Boolean
}

data class PaymentOrder(
    val idempotencyKey: String,
    val contractId: UUID,
    val creditorName: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val reference: String,
)

/** domestic-payment: credit transfer from the provider's payout account. Returns the payment reference. */
interface PayoutPaymentPort {
    suspend fun pay(order: PaymentOrder): String
}

/** sca-service: consumes a challenge bound to [documentSha256] for [partyId] (RTS Art. 5). */
interface ScaVerificationPort {
    suspend fun verify(partyId: UUID, challengeId: String, documentSha256: String): Boolean
}

/** Insurer that converts a premium into a life annuity. */
interface AnnuityInsurerPort {
    suspend fun purchase(
        contractId: UUID,
        premium: BigDecimal,
        birthDate: LocalDate,
        idempotencyKey: String,
    ): AnnuityPolicy
}

data class ClaimantKyc(val name: String, val birthDate: LocalDate, val identityDocumentRef: String, val iban: String)

/** KYC-light check of a beneficiary: identity document matches, and the account is theirs. */
interface BeneficiaryVerificationPort {
    suspend fun verify(kyc: ClaimantKyc): Boolean
}

interface TerminationNoticeRepository {
    suspend fun save(notice: TerminationNotice): TerminationNotice
    suspend fun findById(id: UUID): TerminationNotice?
    suspend fun findOpenByContract(contractId: UUID): List<TerminationNotice>
}

interface PayoutRequestRepository {
    suspend fun save(request: PayoutRequest): PayoutRequest
    suspend fun findById(id: UUID): PayoutRequest?
    suspend fun findByContract(contractId: UUID): List<PayoutRequest>
    suspend fun findInPayment(): List<PayoutRequest>
}

interface DeathClaimRepository {
    suspend fun save(claim: DeathClaim): DeathClaim
    suspend fun findById(id: UUID): DeathClaim?
    suspend fun findByContract(contractId: UUID): DeathClaim?
}

enum class InstructionStatus { PENDING, SENT }

/** One money movement out of a contract, unique by its idempotency key (V4: unique index). */
data class PaymentInstruction(
    val idempotencyKey: String,
    val contractId: UUID,
    val purpose: String,
    val amount: BigDecimal,
    val currency: String,
    val creditorIban: String,
    val status: InstructionStatus,
    val paymentRef: String? = null,
)

interface PaymentInstructionRepository {
    /** Inserts when absent; returns the stored row either way (never a second row for one key). */
    suspend fun recordIfAbsent(instruction: PaymentInstruction): PaymentInstruction
    suspend fun markSent(idempotencyKey: String, paymentRef: String)
    suspend fun findByContract(contractId: UUID): List<PaymentInstruction>
}

/** Starts the durable workflows. Starting one that already runs is a no-op (workflow id = aggregate id). */
interface ExitWorkflowLauncher {
    fun startTermination(noticeId: UUID, noticePeriodDays: Int)
    fun startPayout(payoutId: UUID)
    fun startDeathSettlement(claimId: UUID)
}
