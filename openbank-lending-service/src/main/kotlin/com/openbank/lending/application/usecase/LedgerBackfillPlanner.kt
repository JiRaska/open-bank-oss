// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * One journal the ledger backfill would post (#10746). [reference] is EXACTLY the reference the live
 * lending flow uses for the same economic event, so the ledger's idempotency key collapses a re-run —
 * or a later live posting of the same event — onto the one journal already booked.
 */
data class BackfillLeg(
    val loanId: String,
    val reference: String,
    val kind: PostingKind,
    val amount: BigDecimal,
    val currency: String,
    /** The original business date of the event — carried as the journal's value date. */
    val valueDate: LocalDate,
)

/** The ordered legs for one loan, or the reason it cannot be backfilled mechanically. */
data class LoanBackfillPlan(
    val loanId: String,
    val currency: String,
    val status: String,
    val legs: List<BackfillLeg>,
    val unpaidPrincipal: BigDecimal,
    val unsupportedReason: String? = null,
)

/** Per-currency tie-out of the plan against lending's own book (the risk-engine comparison, ADR-0314). */
data class BackfillTieOut(
    val currency: String,
    /** Σ DISBURSEMENT − Σ PRINCIPAL_REPAYMENT over the plan: what Loans Receivable will hold. */
    val loansReceivableAfter: BigDecimal,
    /** Σ unpaid installment principal in lending for the same loans. */
    val lendingUnpaidPrincipal: BigDecimal,
) {
    val ties: Boolean get() = loansReceivableAfter.compareTo(lendingUnpaidPrincipal) == 0
}

/** The complete dry-run: every leg, the tie-out, and a hash that binds an approval to this exact content. */
data class BackfillPlan(
    val cutoverDate: LocalDate,
    val loans: List<LoanBackfillPlan>,
    val tieOut: List<BackfillTieOut>,
    val planHash: String,
) {
    val legs: List<BackfillLeg> get() = loans.flatMap { it.legs }
    val unsupported: List<LoanBackfillPlan> get() = loans.filter { it.unsupportedReason != null }

    /** Executable only when every loan is mechanically backfillable AND every currency ties out. */
    val executable: Boolean get() = unsupported.isEmpty() && tieOut.all { it.ties } && legs.isNotEmpty()
}

/**
 * Pure reconstruction of the GL journals the live lending flow WOULD have posted for loans whose
 * postings never reached the ledger (#6057/#10746: the real posting adapter was compiled out of the
 * image, so the loans disbursed before #6081 have no ledger history at all).
 *
 * It mirrors the live flow leg for leg, with the live references:
 *
 *  - `loan:<id>:disbursement`               DISBURSEMENT          ([LendingService.disburse])
 *  - `loan:<id>:inst:<n>:accrual`           INTEREST_ACCRUAL      (accrual pass, interest_accrued rows)
 *  - `loan:<id>:inst:<n>:principal`         PRINCIPAL_REPAYMENT   (recordRepayment, paid rows)
 *  - `loan:<id>:inst:<n>:interest`          INTEREST_SETTLEMENT when the row was accrued, else INTEREST
 *  - `loan:<id>:provisioning:<period>`      PROVISIONING, the signed ECL delta vs the prior period
 *
 * GL ONLY. The disbursement's second booking — the borrower credit `loan:<id>:disbursement-credit`
 * through transaction-service — is deliberately NOT reconstructed: it moves customer money, and it
 * never happened for these loans (no transaction, balance movement or deposit sub-ledger line
 * exists). Backfilling it would create customer balances that no customer ever received.
 *
 * Shapes the live flow reaches only through other postings (reschedule forgiveness/capitalisation,
 * write-off, termination, allowance release) are NOT reconstructed: such a loan is reported as
 * unsupported and the whole plan is refused, rather than approximated.
 */
object LedgerBackfillPlanner {

    /** Statuses whose only ledger history is the five leg kinds above. */
    private val SUPPORTED = setOf(LoanStatus.ACTIVE)

    fun plan(
        cutoverDate: LocalDate,
        book: List<Triple<Loan, List<LoanInstallment>, List<LoanProvisioningRecord>>>,
        zone: ZoneId,
    ): BackfillPlan {
        val loans = book.sortedBy { it.first.id.value }.map { (loan, schedule, provisioning) ->
            planLoan(loan, schedule, provisioning, zone)
        }
        val tieOut = loans.filter { it.unsupportedReason == null }
            .groupBy { it.currency }
            .map { (ccy, rows) ->
                val legs = rows.flatMap { it.legs }
                BackfillTieOut(
                    currency = ccy,
                    loansReceivableAfter = legs.sumOf { it.loansReceivableEffect() },
                    lendingUnpaidPrincipal = rows.sumOf { it.unpaidPrincipal },
                )
            }
            .sortedBy { it.currency }
        return BackfillPlan(cutoverDate, loans, tieOut, hash(cutoverDate, loans))
    }

    fun planLoan(
        loan: Loan,
        schedule: List<LoanInstallment>,
        provisioning: List<LoanProvisioningRecord>,
        zone: ZoneId,
    ): LoanBackfillPlan {
        val id = loan.id.value
        val ccy = loan.principal.currency.code
        val rows = schedule.sortedBy { it.number }
        val unpaid = rows.filterNot { it.paid }.sumOf { it.principal.amount }
        val unsupported = unsupportedReason(loan, rows)
        if (unsupported != null) {
            return LoanBackfillPlan(id.toString(), ccy, loan.status.name, emptyList(), unpaid, unsupported)
        }
        val legs = mutableListOf<BackfillLeg>()
        fun leg(reference: String, kind: PostingKind, amount: Money, date: LocalDate) {
            // Zero amounts carry no economic event; the live accrual pass skips them too.
            if (amount.amount.signum() != 0) {
                legs += BackfillLeg(id.toString(), reference, kind, amount.amount, amount.currency.code, date)
            }
        }
        leg("loan:$id:disbursement", PostingKind.DISBURSEMENT, loan.principal, loan.disbursedAt.dateIn(zone))
        rows.filter { it.interestAccrued }.forEach { row ->
            val at = checkNotNull(row.accruedAt) { "installment ${row.number} of $id is accrued without accrued_at" }
            leg("loan:$id:inst:${row.number}:accrual", PostingKind.INTEREST_ACCRUAL, row.interest, at.dateIn(zone))
        }
        rows.filter { it.paid }.forEach { row ->
            val at = checkNotNull(row.paidAt) {
                "installment ${row.number} of $id is paid without paid_at"
            }.dateIn(zone)
            leg("loan:$id:inst:${row.number}:principal", PostingKind.PRINCIPAL_REPAYMENT, row.principal, at)
            val interestKind = if (row.interestAccrued) PostingKind.INTEREST_SETTLEMENT else PostingKind.INTEREST
            leg("loan:$id:inst:${row.number}:interest", interestKind, row.interest, at)
        }
        var prior = Money.zero(ccy)
        provisioning.sortedBy { it.period }.forEach { record ->
            leg(
                "loan:$id:provisioning:${record.period}",
                PostingKind.PROVISIONING,
                record.expectedCreditLoss - prior,
                record.asOf,
            )
            prior = record.expectedCreditLoss
        }
        return LoanBackfillPlan(id.toString(), ccy, loan.status.name, legs, unpaid)
    }

    private fun unsupportedReason(loan: Loan, rows: List<LoanInstallment>): String? = when {
        loan.status !in SUPPORTED ->
            "status ${loan.status} carries postings (write-off/termination/release) this backfill does not reconstruct"
        rows.isEmpty() -> "loan has no repayment schedule"
        rows.map { it.number } != (1..rows.size).toList() ->
            "installment numbers are not contiguous — a reschedule replaced the tail"
        rows.sumOf { it.principal.amount }.compareTo(loan.principal.amount) != 0 ->
            "schedule principal does not sum to the loan principal — the loan was restructured"
        rows.any { it.principal.currency != loan.principal.currency } -> "schedule currency differs from the loan"
        else -> null
    }

    private fun BackfillLeg.loansReceivableEffect(): BigDecimal = when (kind) {
        PostingKind.DISBURSEMENT -> amount
        PostingKind.PRINCIPAL_REPAYMENT -> amount.negate()
        else -> BigDecimal.ZERO
    }

    private fun java.time.OffsetDateTime.dateIn(zone: ZoneId): LocalDate = atZoneSameInstant(zone).toLocalDate()

    /** Binds an approval to the exact legs and cut-over; any change in lending's book changes it. */
    fun hash(cutoverDate: LocalDate, loans: List<LoanBackfillPlan>): String {
        val canonical = buildString {
            append("cutover=").append(cutoverDate).append('\n')
            loans.forEach { loan ->
                append("loan=").append(loan.loanId).append('|').append(loan.unsupportedReason ?: "").append('\n')
                loan.legs.forEach { l ->
                    append(l.reference).append('|').append(l.kind).append('|')
                        .append(l.amount.stripTrailingZeros().toPlainString()).append('|')
                        .append(l.currency).append('|').append(l.valueDate).append('\n')
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The port posting for [leg]: booked on [cutoverDate], value-dated to the original event. */
    fun toPosting(leg: BackfillLeg, partyId: java.util.UUID, cutoverDate: LocalDate): LedgerPosting = LedgerPosting(
        reference = leg.reference,
        partyId = partyId,
        amount = Money.of(leg.amount, leg.currency),
        kind = leg.kind,
        accountingDate = cutoverDate,
        valueDate = leg.valueDate,
    )
}
