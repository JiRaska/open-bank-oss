// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.domain.model

import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.lending.AmortizationMethod
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One not-yet-paid installment of a loan on the book, as of the read's as-of date. */
data class RemainingInstallment(
    val number: Int,
    val dueDate: LocalDate,
    val principal: BigDecimal,
    val interest: BigDecimal,
)

/**
 * One loan as the risk engine's instrument model needs it (ADR-0314 D4): the contract terms plus
 * the remaining contractual schedule, and the Loans Receivable GL code it is carried on.
 *
 * [outstandingPrincipal] is the SUM of [remainingInstallments]' principal — the schedule is the
 * contract, so the outstanding is derived from it rather than stored twice. It is what ledger
 * account [glAccountCode] must hold for this loan; the risk engine checks exactly that.
 *
 * [counterpartyRef] is the party id, passed as an opaque reference: identity stays in
 * party-service (ADR-0314, GDPR note).
 */
data class LoanBookEntry(
    val loanId: UUID,
    val counterpartyRef: UUID,
    val status: LoanStatus,
    val currency: String,
    val glAccountCode: String?,
    val outstandingPrincipal: BigDecimal,
    val nominalAnnualRate: BigDecimal,
    val rateTerms: LoanRateTerms,
    val method: AmortizationMethod,
    val periodsPerYear: Int,
    val disbursedOn: LocalDate,
    val maturityDate: LocalDate?,
    val ifrs9Stage: String?,
    val remainingInstallments: List<RemainingInstallment>,
)

data class LoanBook(val asOf: LocalDate, val loans: List<LoanBookEntry>)

/** The book has more loans than one read may return; the caller must not treat a prefix as the book. */
class LoanBookTooLargeException(limit: Int) :
    IllegalStateException("the loan book exceeds $limit loans; a partial book cannot tie out, refusing to return one")

/**
 * Assembles the loan book as of a date from what lending persists (ADR-0314 D4). Pure: no I/O.
 *
 * As-of reconstruction is BEST EFFORT and the limits are deliberate, not hidden:
 *  - a loan is on the book if it was disbursed on or before [asOf] (UTC date) and its CURRENT
 *    status is not an off-book one ([OFF_BOOK]); lending keeps no status history, so a loan
 *    written off after [asOf] is missing from a past as-of book;
 *  - an installment is remaining if it is unpaid, or was paid AFTER [asOf] (UTC date of `paidAt`);
 *  - a reschedule deletes the unpaid tail (`deleteUnpaid`), so a past as-of across a reschedule
 *    shows the NEW schedule.
 * The risk engine ties every loan out against the ledger at the same as-of; where these limits
 * bite, the loan GL does not tie and the snapshot is UNTIED — the correct outcome, never fudged.
 */
object LoanBookAssembler {

    /** Statuses whose principal the ledger has already credited off Loans Receivable. */
    val OFF_BOOK: Set<LoanStatus> = setOf(
        LoanStatus.CLOSED,
        LoanStatus.WRITTEN_OFF,
        LoanStatus.UNWOUND,
        LoanStatus.SETTLED,
        LoanStatus.WITHDRAWN,
    )

    fun assemble(
        asOf: LocalDate,
        loans: List<Loan>,
        installments: List<LoanInstallment>,
        latestStage: Map<LoanId, String>,
        glCode: (String) -> String?,
    ): LoanBook {
        val byLoan = installments.groupBy { it.loanId }
        val entries = loans
            .filter { it.status !in OFF_BOOK && !utcDate(it.disbursedAt).isAfter(asOf) }
            .sortedBy { it.id.value }
            .map { loan ->
                val schedule = byLoan[loan.id].orEmpty().sortedBy { it.number }
                val remaining = schedule
                    .filter { !it.paid || it.paidAt?.let { at -> utcDate(at).isAfter(asOf) } == true }
                    .map { RemainingInstallment(it.number, it.dueDate, it.principal.amount, it.interest.amount) }
                val currency = loan.principal.currency.code
                val zero = BigDecimal.ZERO.setScale(loan.principal.currency.defaultFractionDigits)
                LoanBookEntry(
                    loanId = loan.id.value,
                    counterpartyRef = loan.partyId,
                    status = loan.status,
                    currency = currency,
                    glAccountCode = glCode(currency),
                    outstandingPrincipal = remaining.fold(zero) { acc, i -> acc.add(i.principal) },
                    nominalAnnualRate = loan.nominalAnnualRate,
                    rateTerms = loan.rateTerms,
                    method = loan.method,
                    periodsPerYear = loan.periodsPerYear,
                    disbursedOn = utcDate(loan.disbursedAt),
                    maturityDate = schedule.lastOrNull()?.dueDate,
                    ifrs9Stage = latestStage[loan.id],
                    remainingInstallments = remaining,
                )
            }
        return LoanBook(asOf, entries)
    }

    private fun utcDate(at: OffsetDateTime): LocalDate = at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
}
