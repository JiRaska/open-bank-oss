// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.incentive

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

enum class ClaimStatus { PENDING, SUBMITTED, RECEIVED, REJECTED, RETURNED }

/**
 * A claim for one incentive, for one contract, for one incentive period (ADR-0334 S3). The triple
 * `(contractId, incentiveId, period)` is unique: a period is claimed once, and a re-run of the
 * monthly job finds the existing claim instead of filing a second one.
 *
 * Lifecycle: `PENDING → SUBMITTED → RECEIVED | REJECTED`, and `RECEIVED → RETURNED` when the money
 * goes back to the agency (clawback on early exit, or an agency correction). A rejected claim is
 * terminal; a re-claim after correcting the cause is a new period's business, not a resurrection.
 */
data class IncentiveClaim(
    val id: UUID,
    val contractId: UUID,
    val incentiveId: String,
    val period: YearMonth,
    /** The contribution the claim was computed from. */
    val basis: BigDecimal,
    val claimedAmount: BigDecimal,
    val currency: String,
    val status: ClaimStatus,
    val batchId: UUID? = null,
    val receivedAmount: BigDecimal? = null,
    val rejectionReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(claimedAmount.signum() > 0) { "a claim must be for a positive amount" }
        require(status == ClaimStatus.PENDING || batchId != null) { "a filed claim belongs to a batch" }
    }

    fun submit(batch: UUID, at: Instant): IncentiveClaim {
        check(status == ClaimStatus.PENDING) { "claim $id is $status, only PENDING can be submitted" }
        return copy(status = ClaimStatus.SUBMITTED, batchId = batch, updatedAt = at)
    }

    /** The agency may pay less than claimed (it recomputes); never more. */
    fun receive(amount: BigDecimal, at: Instant): IncentiveClaim {
        check(status == ClaimStatus.SUBMITTED) { "claim $id is $status, only SUBMITTED can be received" }
        require(amount.signum() > 0 && amount <= claimedAmount) {
            "received amount $amount must be positive and at most the claimed $claimedAmount"
        }
        return copy(status = ClaimStatus.RECEIVED, receivedAmount = amount, updatedAt = at)
    }

    fun reject(reason: String, at: Instant): IncentiveClaim {
        check(status == ClaimStatus.SUBMITTED) { "claim $id is $status, only SUBMITTED can be rejected" }
        require(reason.isNotBlank()) { "a rejection carries the agency's reason" }
        return copy(status = ClaimStatus.REJECTED, rejectionReason = reason, updatedAt = at)
    }

    fun markReturned(at: Instant): IncentiveClaim {
        check(status == ClaimStatus.RECEIVED) { "claim $id is $status, only RECEIVED can be returned" }
        return copy(status = ClaimStatus.RETURNED, updatedAt = at)
    }
}

enum class ClaimBatchStatus { SUBMITTED, RECONCILED }

/** One filing to a claim channel: the claims it carries and the rendered payload, kept as evidence. */
data class ClaimBatch(
    val id: UUID,
    val claimFormat: String,
    val period: YearMonth,
    val claimIds: List<UUID>,
    val payload: String,
    val channelReference: String?,
    val status: ClaimBatchStatus,
    val createdAt: Instant,
)

enum class LedgerEntryKind {
    /** Incentive money received and credited to the contract. */
    RECEIVED,

    /** Incentive money returned to the agency (clawback or correction). */
    RETURNED,
}

/**
 * Append-only incentive ledger, per contract (ADR-0334 S3). It is the record S5's early
 * termination reads to compute what must be returned: the balance per incentive is
 * `Σ RECEIVED − Σ RETURNED`, and nothing ever edits a row.
 */
data class IncentiveLedgerEntry(
    val id: UUID,
    val contractId: UUID,
    val incentiveId: String,
    val claimId: UUID?,
    val kind: LedgerEntryKind,
    val amount: BigDecimal,
    val taxYear: Int,
    val period: YearMonth,
    val occurredAt: Instant,
    /** Set on entries written for an external instruction (S5 clawback settlement); unique when set. */
    val idempotencyKey: String? = null,
) {
    init {
        require(amount.signum() > 0) { "a ledger entry amount is positive; the kind carries the sign" }
    }

    val signed: BigDecimal get() = if (kind == LedgerEntryKind.RECEIVED) amount else amount.negate()
}

/** Employer contributions of one employer in a tax year, split into the exempt and taxable part. */
data class EmployerExemption(
    val employerPartyId: UUID,
    val contributed: BigDecimal,
    val exempt: BigDecimal,
    val taxable: BigDecimal,
)

/**
 * Per contract, per tax year: what came in by source, what incentives were received, and the
 * deductible / exempt figures the annual tax certificate states (ADR-0334 S3). Live until
 * [finalizedAt]; the certificate is issued from the finalized snapshot only.
 */
data class TaxYearSummary(
    val contractId: UUID,
    val taxYear: Int,
    val currency: String,
    val participantContributions: BigDecimal,
    val employerContributions: BigDecimal,
    val stateIncentives: BigDecimal,
    val transferIn: BigDecimal,
    val deductibleAmount: BigDecimal,
    val indicativeTaxSaving: BigDecimal?,
    val sharedCapUsedElsewhere: Map<String, BigDecimal>,
    val employerExemptions: List<EmployerExemption>,
    val finalizedAt: Instant? = null,
    val certificateDocumentId: String? = null,
) {
    val employerExempt: BigDecimal get() = employerExemptions.fold(BigDecimal.ZERO) { a, e -> a + e.exempt }
    val employerTaxable: BigDecimal get() = employerExemptions.fold(BigDecimal.ZERO) { a, e -> a + e.taxable }
}

/** What must go back on early exit for one incentive (consumed by S5). */
enum class ClawbackKind { RETURN_TO_AGENCY, TAX_RECAPTURE }

/**
 * Field-for-field what S5's `IncentiveClawbackPort.balance` returns (`domain.exit.IncentiveBalance`),
 * so S5's adapter is a plain mapping with no arithmetic of its own.
 */
data class ClawbackBalance(
    val stateIncentivesToReturn: BigDecimal,
    val stateIncentivesReceived: BigDecimal,
    val deductedContributionsByYear: Map<Int, BigDecimal>,
    val employerExemptByYear: Map<Int, BigDecimal>,
    val ownContributionsNotDeducted: BigDecimal,
)

data class ClawbackItem(val incentiveId: String, val kind: ClawbackKind, val amount: BigDecimal, val taxYears: Set<Int>)
