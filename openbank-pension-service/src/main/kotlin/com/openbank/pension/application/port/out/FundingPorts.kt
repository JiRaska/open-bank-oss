// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.domain.contribution.UnmatchedPayment
import com.openbank.pension.domain.contribution.UnmatchedStatus
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.incentive.IncentiveLedgerEntry
import com.openbank.pension.domain.incentive.TaxYearSummary
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Own persistence (ADR-0334 S3)
// ---------------------------------------------------------------------------------------------

/** A lightweight read of a contract for funding decisions — who owns it, and in which pack. */
data class ContractFundingView(
    val contractId: UUID,
    val participantPartyId: UUID,
    val jurisdiction: String,
    val productLine: String,
    val packVersion: Int,
    val status: String,
    val currency: String,
    val createdAt: java.time.Instant,
)

interface ContractReferenceRepository {
    /** The contract's payment reference, allocating one on first use. Stable for the contract's life. */
    suspend fun referenceFor(contractId: UUID): String

    suspend fun contractFor(reference: String): UUID?
}

interface ContributionRepository {
    /**
     * Inserts unless a contribution with the same paymentId exists; returns the stored one and
     * whether this call created it. Idempotency lives in the unique index, not in a prior read.
     */
    suspend fun insertIfAbsent(contribution: Contribution): Pair<Contribution, Boolean>

    suspend fun setSubscriptionOrder(contributionId: UUID, orderId: String)

    suspend fun byContract(contractId: UUID): List<Contribution>

    suspend fun byContractAndYear(contractId: UUID, taxYear: Int): List<Contribution>

    suspend fun byContractAndRange(contractId: UUID, from: LocalDate, toExclusive: LocalDate): List<Contribution>
}

interface UnmatchedPaymentRepository {
    /** Idempotent on the payment id — a re-delivered payment does not queue twice. */
    suspend fun insertIfAbsent(payment: UnmatchedPayment): UnmatchedPayment

    suspend fun findById(id: UUID): UnmatchedPayment?

    suspend fun list(status: UnmatchedStatus?): List<UnmatchedPayment>

    suspend fun update(payment: UnmatchedPayment)
}

interface IncentiveClaimRepository {
    /** Idempotent on (contract, incentive, period); the flag says whether this call created it. */
    suspend fun insertIfAbsent(claim: IncentiveClaim): Pair<IncentiveClaim, Boolean>

    suspend fun findById(id: UUID): IncentiveClaim?

    suspend fun byStatus(status: ClaimStatus): List<IncentiveClaim>

    suspend fun byContract(contractId: UUID): List<IncentiveClaim>

    suspend fun update(claim: IncentiveClaim)
}

interface ClaimBatchRepository {
    /**
     * Files [batch] atomically: in ONE transaction, moves every claim it lists from PENDING to
     * SUBMITTED and stores the batch. If any listed claim is no longer PENDING (a concurrent run
     * filed it first) nothing is written and the answer is false — a claim is never in two batches.
     */
    suspend fun fileAtomically(batch: ClaimBatch, at: java.time.Instant): Boolean

    suspend fun findById(id: UUID): ClaimBatch?

    suspend fun list(): List<ClaimBatch>

    suspend fun update(batch: ClaimBatch)
}

interface IncentiveLedgerRepository {
    suspend fun append(entry: IncentiveLedgerEntry)

    suspend fun byContract(contractId: UUID): List<IncentiveLedgerEntry>
}

interface TaxYearSummaryRepository {
    suspend fun findFinal(contractId: UUID, taxYear: Int): TaxYearSummary?

    suspend fun saveFinal(summary: TaxYearSummary)

    /** Participant-declared shared-cap usage at OTHER providers, per group, for a tax year. */
    suspend fun externalCapUsage(participantPartyId: UUID, taxYear: Int): Map<String, BigDecimal>

    suspend fun declareExternalCapUsage(participantPartyId: UUID, taxYear: Int, usage: Map<String, BigDecimal>)
}

/**
 * Which employers a participant has authorised to pay into a contract. An employer bulk line for a
 * contract its employer is not enrolled on is never credited: a reference alone must not let one
 * business push money (and its exemption) onto a stranger's contract.
 */
interface EmployerEnrolmentRepository {
    suspend fun enrol(contractId: UUID, employerPartyId: UUID)

    suspend fun isEnrolled(contractId: UUID, employerPartyId: UUID): Boolean
}

interface ContractFundingDirectory {
    suspend fun find(contractId: UUID): ContractFundingView?

    /** Every contract of the participant, oldest first — the shared-cap allocation order. */
    suspend fun byParticipant(participantPartyId: UUID): List<ContractFundingView>

    /** Contracts that can receive money and earn incentives (ACTIVE or SUSPENDED-with-arrears). */
    suspend fun fundable(): List<ContractFundingView>
}

// ---------------------------------------------------------------------------------------------
// Other services (ADR-0334 §4 Contribute) — ports; adapters may be stubs until the API exists.
// ---------------------------------------------------------------------------------------------

/** pension-fund-service (S4): turn credited money into units at the next NAV. */
interface FundAdministrationPort {
    /** Idempotent on [idempotencyKey]; returns the fund-side order id. */
    suspend fun placeSubscription(
        contractId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): String
}

data class MandateRequest(
    val contractId: UUID,
    val participantPartyId: UUID,
    val kind: MandateKind,
    val debtorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val reference: String,
    val firstCollection: LocalDate,
)

/** standing-order-service / sdd-service: set up the participant's regular payment. */
interface PaymentMandatePort {
    suspend fun setUp(request: MandateRequest): String
}

/** kyb-service / party: resolve an employer and confirm it is a verified business. */
interface EmployerDirectoryPort {
    suspend fun isVerifiedEmployer(employerPartyId: UUID): Boolean
}

/** A claim line the agency answered in its receipt file. */
data class ClaimReceiptLine(val claimId: UUID, val accepted: Boolean, val amount: BigDecimal?, val reason: String?)

data class RenderedClaimBatch(val payload: String, val channelReference: String?)

/**
 * One claim channel adapter per wire format (ADR-0334 S3: batch-to-agency). The pack names the
 * format; the adapter is chosen by it, so a jurisdiction is never named in Kotlin.
 */
interface StateIncentiveClaimPort {
    val claimFormat: String

    suspend fun submit(
        period: YearMonth,
        claims: List<IncentiveClaim>,
        references: Map<UUID, String>,
    ): RenderedClaimBatch

    /** Parses the agency's receipt / response file for a batch this adapter rendered. */
    fun parseReceipt(payload: String): List<ClaimReceiptLine>
}

/** document-service: render the annual tax certificate. Returns the document id. */
interface TaxCertificateDocumentPort {
    suspend fun generate(summary: TaxYearSummary, participantPartyId: UUID, contractReference: String): String
}
