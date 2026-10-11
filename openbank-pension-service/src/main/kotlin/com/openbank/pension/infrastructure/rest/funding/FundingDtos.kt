// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.pension.application.usecase.IncentiveBalance
import com.openbank.pension.domain.contribution.Contribution
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.EmployerLineResult
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.domain.contribution.UnmatchedPayment
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.ClawbackItem
import com.openbank.pension.domain.incentive.EmployerExemption
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.incentive.TaxYearSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Requests: every field nullable and checked in the resource, so an absent field is a 400 with a
// message rather than a Jackson/Kotlin NPE rendered as 500 (#3104).

data class IncomingPaymentRequest(
    val paymentId: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val valueDate: LocalDate? = null,
    val reference: String? = null,
    val channel: ContributionChannel? = null,
    val payerAccount: String? = null,
)

data class EmployerBatchLineRequest(val contractReference: String? = null, val amount: BigDecimal? = null)

data class EmployerBatchRequest(
    val employerPartyId: UUID? = null,
    val payment: IncomingPaymentRequest? = null,
    val lines: List<EmployerBatchLineRequest?>? = null,
)

data class AssignUnmatchedRequest(val contractId: UUID? = null)

data class ClaimRunRequest(val period: String? = null)

data class ReceiptFileRequest(val payload: String? = null)

data class MandateSetupRequest(
    val kind: MandateKind? = null,
    val debtorIban: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val firstCollection: LocalDate? = null,
    /** Account holder's name; required for a SEPA direct-debit mandate (#12378). */
    val debtorName: String? = null,
    /** Single-use challenge signed over `pension-mandate-setup:<documentHash>` (ADR-0335). */
    val scaChallengeId: String? = null,
)

data class MandateCancelRequest(val scaChallengeId: String? = null)

data class ExternalCapUsageRequest(val usage: Map<String, BigDecimal?>? = null)

// Responses

data class PaymentReferenceResponse(val contractId: UUID, val reference: String)

data class MandateResponse(
    /** The downstream standing-order / sdd mandate id. */
    val mandateId: String,
    /** pension-service's own id of the mandate — the one the cancel route takes (#12378). */
    val id: java.util.UUID? = null,
    val status: String? = null,
)

data class ContributionResponse(
    val id: UUID,
    val contractId: UUID,
    val paymentId: String,
    val source: String,
    val channel: String,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val taxYear: Int,
    val employerPartyId: UUID?,
    val subscriptionOrderId: String?,
    val receivedAt: Instant,
) {
    companion object {
        fun from(c: Contribution) = ContributionResponse(
            c.id, c.contractId, c.paymentId, c.source.name, c.channel.name, c.amount, c.currency, c.valueDate,
            c.taxYear,
            c.employerPartyId, c.subscriptionOrderId, c.receivedAt,
        )
    }
}

data class ReceiptResponse(
    val outcome: String,
    val contribution: ContributionResponse?,
    val unmatched: UnmatchedResponse?,
)

data class UnmatchedResponse(
    val id: UUID,
    val paymentId: String,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val reference: String?,
    val reason: String,
    val status: String,
    val resolvedContractId: UUID?,
    val resolvedBy: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(u: UnmatchedPayment) = UnmatchedResponse(
            u.id, u.payment.paymentId, u.payment.amount, u.payment.currency, u.payment.valueDate, u.payment.reference,
            u.reason.name, u.status.name, u.resolvedContractId, u.resolvedBy, u.createdAt,
        )
    }
}

data class EmployerLineResponse(
    val lineNo: Int,
    val contractReference: String,
    val amount: BigDecimal,
    val outcome: String,
) {
    companion object {
        fun from(r: EmployerLineResult) = EmployerLineResponse(r.lineNo, r.contractReference, r.amount, r.outcome.name)
    }
}

data class ClaimResponse(
    val id: UUID,
    val contractId: UUID,
    val incentiveId: String,
    val period: String,
    val basis: BigDecimal,
    val claimedAmount: BigDecimal,
    val currency: String,
    val status: String,
    val batchId: UUID?,
    val receivedAmount: BigDecimal?,
    val rejectionReason: String?,
) {
    companion object {
        fun from(c: IncentiveClaim) = ClaimResponse(
            c.id, c.contractId, c.incentiveId, c.period.toString(), c.basis, c.claimedAmount, c.currency, c.status.name,
            c.batchId, c.receivedAmount, c.rejectionReason,
        )
    }
}

data class BalanceResponse(
    val incentiveId: String,
    val received: BigDecimal,
    val returned: BigDecimal,
    val net: BigDecimal,
) {
    companion object {
        fun from(b: IncentiveBalance) = BalanceResponse(b.incentiveId, b.received, b.returned, b.net)
    }
}

data class IncentiveStatusResponse(val claims: List<ClaimResponse>, val balances: List<BalanceResponse>)

data class ClaimBatchResponse(
    val id: UUID,
    val claimFormat: String,
    val period: String,
    val claimIds: List<UUID>,
    val status: String,
    val payload: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(b: ClaimBatch) = ClaimBatchResponse(
            b.id,
            b.claimFormat,
            b.period.toString(),
            b.claimIds,
            b.status.name,
            b.payload,
            b.createdAt,
        )
    }
}

data class ClaimRunResponse(
    val claimsCreated: Int,
    val batches: List<ClaimBatchResponse>,
    val unfiledFormats: Set<String>,
)

data class EmployerExemptionResponse(
    val employerPartyId: UUID,
    val contributed: BigDecimal,
    val exempt: BigDecimal,
    val taxable: BigDecimal,
) {
    companion object {
        fun from(e: EmployerExemption) =
            EmployerExemptionResponse(e.employerPartyId, e.contributed, e.exempt, e.taxable)
    }
}

data class TaxYearSummaryResponse(
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
    val employerExemptions: List<EmployerExemptionResponse>,
    val employerExempt: BigDecimal,
    val employerTaxable: BigDecimal,
    val finalized: Boolean,
    val certificateDocumentId: String?,
) {
    companion object {
        fun from(s: TaxYearSummary) = TaxYearSummaryResponse(
            s.contractId, s.taxYear, s.currency, s.participantContributions, s.employerContributions, s.stateIncentives,
            s.transferIn, s.deductibleAmount, s.indicativeTaxSaving, s.sharedCapUsedElsewhere,
            s.employerExemptions.map(EmployerExemptionResponse::from), s.employerExempt, s.employerTaxable,
            s.finalizedAt != null, s.certificateDocumentId,
        )
    }
}

data class ClawbackItemResponse(
    val incentiveId: String,
    val kind: String,
    val amount: BigDecimal,
    val taxYears: List<Int>,
) {
    companion object {
        fun from(c: ClawbackItem) = ClawbackItemResponse(c.incentiveId, c.kind.name, c.amount, c.taxYears.sorted())
    }
}
