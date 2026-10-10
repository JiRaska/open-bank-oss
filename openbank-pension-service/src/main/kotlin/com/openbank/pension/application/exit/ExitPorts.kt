// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.domain.exit.AnnuityPolicy
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.domain.exit.IncentiveBalance
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.TerminationNotice
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * Outbound ports of the exit slice (ADR-0334 S5). Every money-moving call carries an
 * idempotency key derived from the aggregate and the step (never random), so a Temporal retry of
 * an activity repeats the SAME instruction and the receiving side deduplicates it.
 */

/** The incentive/clawback ledger slice S3 builds; read for quotes, settled on exit. */
interface IncentiveClawbackPort {
    suspend fun balance(contractId: UUID, asOf: LocalDate): IncentiveBalance

    /** Returns [amount] of state incentives to the state (claim channel of the pack). */
    suspend fun settleClawback(contractId: UUID, amount: BigDecimal, idempotencyKey: String)
}

/**
 * Tells the participant, on their already-known channel, that the payout account changed and from
 * when it applies (ADR-0334 S8 fraud control). Throws when it cannot deliver: the change is then
 * refused, so no account change ever takes effect unannounced.
 */
interface ParticipantNotificationPort {
    suspend fun payoutAccountChanged(
        partyId: UUID,
        contractId: UUID,
        payoutId: UUID,
        accountLast4: String,
        effectiveFrom: LocalDate,
    )
}

/**
 * A write lost a race: the row changed since it was read (optimistic lock, ADR-0334 S8). 409 with
 * Retry-After at the edge; re-read and retried by [PayoutService] internally.
 *
 * Deliberately NOT an IllegalStateException: the exit workflows mark IllegalState as do-not-retry,
 * so an instalment activity that lost to a concurrent account change used to FAIL the payout
 * workflow for good instead of re-reading. Every activity step is idempotent, so a retry is safe.
 */
class ExitConcurrentUpdateException(message: String) : RuntimeException(message)

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
/**
 * The pension operation a document-bound SCA challenge was raised for. sca-service reserves the
 * `pension-` namespace for pension-service's identity (ADR-0335): the device signs
 * `approvalRequestId = pension-<code>:<documentSha256>`, so a challenge raised for one operation can
 * never be spent on another, and no other consumer can spend a pension challenge.
 */
enum class ScaOperation(val code: String) {
    /** Termination signing, payout confirmation, payout-account change (hash covers quote + IBAN). */
    EXIT("exit"),
    SCHEDULE_CHANGE("schedule-change"),
    STRATEGY_CHANGE("strategy-change"),
    BENEFICIARY_CHANGE("beneficiary-change"),
    ANNUITY_SELECTION("annuity-selection"),
    ANNUITY_CANCELLATION("annuity-cancellation"),
    MANDATE_SETUP("mandate-setup"),
    EMPLOYER_ENROLMENT("employer-enrolment"),
    MANDATE_CANCELLATION("mandate-cancellation"),
    ;

    /** The exact `approvalRequestId` the client must raise the sca-service challenge with. */
    fun approvalRequestId(documentSha256: String): String = "$PREFIX$code:${documentSha256.lowercase()}"

    companion object {
        const val PREFIX = "pension-"
    }
}

interface ScaVerificationPort {
    suspend fun verify(partyId: UUID, challengeId: String, documentSha256: String, operation: ScaOperation): Boolean
}

/** How an ANNUITY payout's money ended up (#12383). */
sealed interface AnnuityPlacement {
    /** The partner issued the policy for the premium that was sent. */
    data class Issued(val policy: AnnuityPolicy) : AnnuityPlacement

    /** No policy; the pack sends the money to the participant's signed account instead. */
    data class ReturnedToClient(val paymentRef: String) : AnnuityPlacement

    /** No policy; the pack returns the money to the contract (re-invested), which reopens. */
    data object ReturnedToContract : AnnuityPlacement
}

/**
 * The annuity marketplace as the exit slice sees it (#12383, replaces the single-insurer stub):
 * a confirmation needs a binding SCA selection, and execution places the premium with the
 * selected partner. [place] is idempotent and resumable; it throws [AnnuityStepPendingException]
 * while the partner has not answered yet, so the workflow activity retries it.
 */
interface AnnuityPlacementPort {
    suspend fun requireBindingSelection(payoutId: UUID, premium: BigDecimal)

    suspend fun place(
        payout: PayoutRequest,
        contract: com.openbank.pension.domain.model.PensionContract,
    ): AnnuityPlacement
}

/** The partner has not decided yet (policy or refund pending). NOT an IllegalStateException: it is retried. */
class AnnuityStepPendingException(message: String) : RuntimeException(message)

data class ClaimantKyc(
    val name: String,
    val birthDate: LocalDate,
    val identityDocumentRef: String,
    val iban: String,
    /** The designated claimant's party, set from the claim (never from the request); null = not a customer. */
    val partyId: UUID? = null,
)

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

    /** Operator queue: newest first, optionally by status and contract. */
    suspend fun list(status: PayoutStatus?, contractId: UUID?, limit: Int): List<PayoutRequest>
}

interface DeathClaimRepository {
    suspend fun save(claim: DeathClaim): DeathClaim
    suspend fun findById(id: UUID): DeathClaim?
    suspend fun findByContract(contractId: UUID): DeathClaim?

    /** Operator queue: newest first, optionally by status. */
    suspend fun list(status: DeathClaimStatus?, limit: Int): List<DeathClaim>
}

/** SETTLED / REJECTED are written back from domestic-payment's status events (#12378, V7). */
enum class InstructionStatus { PENDING, SENT, SETTLED, REJECTED }

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
