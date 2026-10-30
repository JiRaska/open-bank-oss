// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.rest

import com.openbank.pension.application.exit.EligibilityView
import com.openbank.pension.domain.exit.Claimant
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.Installment
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.TerminationNotice
import com.openbank.pension.domain.exit.TerminationQuote
import com.openbank.pension.domain.model.PayoutForm
import org.eclipse.microprofile.openapi.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Request bodies are all-nullable: absent JSON fields must reach the body as null and be refused
 * with a 400 there, never as a Kotlin NPE before it (#3104).
 */

@Schema(name = "ExitSignRequest")
data class SignRequest(val scaChallengeId: String? = null, val payoutIban: String? = null)

data class PayoutQuoteRequest(val form: PayoutForm? = null, val amount: BigDecimal? = null, val months: Int? = null)

data class NotifyDeathRequest(
    val contractId: UUID? = null,
    val dateOfDeath: LocalDate? = null,
    val evidenceRef: String? = null,
)

data class ClaimantRequest(
    val name: String? = null,
    val partyId: UUID? = null,
    val sharePercent: BigDecimal? = null,
    val estate: Boolean? = null,
)

data class ClaimantsRequest(val claimants: List<ClaimantRequest?>? = null)

data class VerifyClaimantRequest(
    val name: String? = null,
    val birthDate: LocalDate? = null,
    val identityDocumentRef: String? = null,
    val iban: String? = null,
)

data class EligibilityResponse(
    val conditionsMet: Boolean,
    val ageYears: Int,
    val durationMonths: Long,
    val reasons: List<String>,
    val allowedForms: List<PayoutForm>,
    val partialWithdrawalAllowed: Boolean,
) {
    companion object {
        fun from(v: EligibilityView) = EligibilityResponse(
            v.eligibility.conditionsMet,
            v.eligibility.ageYears,
            v.eligibility.durationMonths,
            v.eligibility.reasons,
            v.allowedForms.sorted(),
            v.partialAllowed,
        )
    }
}

data class TerminationQuoteResponse(
    val redemptionValue: BigDecimal,
    val surrenderFee: BigDecimal,
    val incentiveReturn: BigDecimal,
    val deductionRecapture: BigDecimal,
    val employerExemptRecapture: BigDecimal,
    val totalDeductions: BigDecimal,
    val netPayout: BigDecimal,
    val shortfall: BigDecimal,
    val currency: String,
    val packVersion: Int,
) {
    companion object {
        fun from(q: TerminationQuote) = TerminationQuoteResponse(
            q.redemptionValue, q.surrenderFee, q.incentiveReturn, q.deductionRecapture, q.employerExemptRecapture,
            q.totalDeductions, q.netPayout, q.shortfall, q.currency, q.packVersion,
        )
    }
}

data class TerminationResponse(
    val noticeId: UUID,
    val contractId: UUID,
    val status: String,
    val quote: TerminationQuoteResponse,
    /** What the participant signs: SCA dynamic linking binds the challenge to this hash. */
    val quoteHash: String,
    val quoteExpiresAt: Instant,
    val signedAt: Instant?,
    val effectiveDate: LocalDate?,
    val paymentRef: String?,
) {
    companion object {
        fun from(n: TerminationNotice) = TerminationResponse(
            n.id, n.contractId, n.status.name, TerminationQuoteResponse.from(n.quote), n.quoteHash, n.quoteExpiresAt,
            n.signedAt, n.effectiveDate, n.paymentRef,
        )
    }
}

data class InstallmentResponse(
    val seq: Int,
    val dueDate: LocalDate,
    val gross: BigDecimal,
    val tax: BigDecimal,
    val net: BigDecimal,
    val status: String,
) {
    companion object {
        fun from(i: Installment) = InstallmentResponse(i.seq, i.dueDate, i.gross, i.tax, i.net, i.status.name)
    }
}

data class PayoutResponse(
    val payoutId: UUID,
    val contractId: UUID,
    val form: PayoutForm,
    val status: String,
    val currentValue: BigDecimal,
    val grossAmount: BigDecimal,
    val taxBase: String,
    val taxableAmount: BigDecimal,
    val taxWithheld: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    val months: Int?,
    val quoteHash: String,
    val quoteExpiresAt: Instant,
    val installments: List<InstallmentResponse>,
    val annuityPolicyRef: String?,
    val annuityMonthlyAmount: BigDecimal?,
    val paymentRef: String?,
    /** Last four characters of the signed payout account (never the full IBAN). */
    val payoutAccountLast4: String?,
    /** A held account change: last four characters and the first due date it applies to. */
    val pendingAccountLast4: String?,
    val pendingAccountFrom: java.time.LocalDate?,
) {
    companion object {
        fun from(p: PayoutRequest) = PayoutResponse(
            p.id, p.contractId, p.form, p.status.name, p.quote.currentValue, p.quote.grossAmount, p.quote.taxBase.name,
            p.quote.taxableAmount, p.quote.taxWithheld, p.quote.netAmount, p.quote.currency, p.quote.months,
            p.quoteHash,
            p.quoteExpiresAt, p.schedule?.installments.orEmpty().map(InstallmentResponse::from), p.annuity?.policyRef,
            p.annuity?.monthlyAmount, p.paymentRef, p.payoutIban?.takeLast(LAST4),
            p.pendingPayoutIban?.takeLast(LAST4), p.pendingPayoutIbanFrom,
        )

        private const val LAST4 = 4
    }
}

/** The participant's payout statement: what was quoted, what has been paid, what remains. */
data class PayoutStatementResponse(
    val payoutId: UUID,
    val form: PayoutForm,
    val status: String,
    val currency: String,
    val grossQuoted: BigDecimal,
    val taxWithheldQuoted: BigDecimal,
    val netQuoted: BigDecimal,
    val netPaid: BigDecimal,
    val taxWithheldPaid: BigDecimal,
    val netOutstanding: BigDecimal,
    val installmentsPaid: Int,
    val installmentsTotal: Int,
) {
    companion object {
        fun from(p: PayoutRequest): PayoutStatementResponse {
            val schedule = p.schedule
            val (netPaid, taxPaid, count) = when {
                schedule != null -> {
                    val paid = schedule.installments.filter { it.paymentRef != null }
                    Triple(
                        paid.fold(BigDecimal.ZERO) { a, i -> a + i.net },
                        paid.fold(BigDecimal.ZERO) { a, i ->
                            a +
                                i.tax
                        },
                        paid.size,
                    )
                }
                p.paymentRef != null || p.annuity != null -> Triple(p.quote.netAmount, p.quote.taxWithheld, 1)
                else -> Triple(BigDecimal.ZERO, BigDecimal.ZERO, 0)
            }
            val scale = p.quote.netAmount.scale()
            return PayoutStatementResponse(
                p.id, p.form, p.status.name, p.quote.currency, p.quote.grossAmount, p.quote.taxWithheld,
                p.quote.netAmount,
                netPaid.setScale(scale), taxPaid.setScale(scale), (p.quote.netAmount - netPaid).setScale(scale), count,
                schedule?.installments?.size ?: 1,
            )
        }
    }
}

data class ClaimantResponse(
    val claimantId: UUID,
    val name: String,
    val sharePercent: BigDecimal,
    val estate: Boolean,
    val verification: String,
    val gross: BigDecimal?,
    val tax: BigDecimal?,
    val net: BigDecimal?,
    val paymentRef: String?,
) {
    companion object {
        fun from(c: Claimant) = ClaimantResponse(
            c.id, c.name, c.sharePercent, c.estate, c.verification.name, c.gross, c.tax, c.net, c.paymentRef,
        )
    }
}

data class DeathClaimResponse(
    val claimId: UUID,
    val contractId: UUID,
    val status: String,
    val dateOfDeath: LocalDate,
    val notifiedBy: String,
    val approvedBy: String?,
    val valuation: BigDecimal?,
    val incentiveReturn: BigDecimal,
    val claimants: List<ClaimantResponse>,
) {
    companion object {
        fun from(c: DeathClaim) = DeathClaimResponse(
            c.id, c.contractId, c.status.name, c.dateOfDeath, c.notifiedBy, c.approvedBy, c.valuation,
            c.incentiveReturn,
            c.claimants.map(ClaimantResponse::from),
        )
    }
}
