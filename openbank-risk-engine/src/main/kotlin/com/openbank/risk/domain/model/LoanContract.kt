// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.curve.CurveIndex
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One loan exactly as lending's loan-book read returns it (`GET /api/v1/lending/loan-book`), before
 * it becomes an [Instrument]. Kept separate from the instrument so the input hash is over what
 * ARRIVED, not over the engine's interpretation of it.
 *
 * WHY THIS IS PULLED, NOT CONSUMED (a deliberate deviation from ADR-0314 D1 for this slice): D1
 * says positions come from events. `loan.disbursed` carries the terms, but no event carries a
 * loan's REMAINING schedule — repayments, reschedules and write-offs change it and only lending's
 * database holds the result. So the engine reads the book at snapshot time, puts that response in
 * the canonical input hash (a replay is still keyed on what was read), and ties every loan out
 * against the ledger. Retiring the pull needs a schedule-carrying event, the same path D1 already
 * describes for reference data.
 */
data class LoanContract(
    val loanId: UUID,
    val counterpartyRef: UUID,
    val status: String,
    val currency: String,
    val glAccountCode: String?,
    val outstandingPrincipal: BigDecimal,
    val nominalAnnualRate: BigDecimal,
    val rateType: String,
    val rateIndex: String?,
    val spread: BigDecimal?,
    val resetFrequencyMonths: Int?,
    val nextResetDate: LocalDate?,
    val method: String,
    val periodsPerYear: Int,
    val disbursedOn: LocalDate,
    val maturityDate: LocalDate?,
    val ifrs9Stage: String?,
    val remainingInstallments: List<ScheduledInstallment>,
)

/** A lending response the engine cannot interpret — a broken contract, never silently repaired. */
class InvalidLoanContractException(loanId: UUID, reason: String) :
    IllegalStateException("lending loan $loanId: $reason")

/**
 * Maps lending's loan contracts to [Instrument]s (ADR-0314 D4).
 *
 * - ANNUITY and EQUAL_PRINCIPAL are [InstrumentKind.AMORTISING_LOAN]; BULLET is [InstrumentKind.BULLET];
 * - outstanding is the principal, positive (an asset in debit − credit), and MUST equal the sum
 *   of the remaining installments' principal — lending derives it that way, so a disagreement is
 *   a broken contract and the snapshot fails rather than choosing one of the two;
 * - FLOATING must name an index the engine has a curve type for ([CurveIndex]) and carry its
 *   spread and reset terms; accrued interest and the loan-loss allowance are NOT on the
 *   instrument — they stay GL-level positions on their own accounts.
 */
object LoanInstrumentMapper {

    fun toInstrument(loan: LoanContract): Instrument {
        val method = runCatching { AmortizationMethod.valueOf(loan.method) }
            .getOrElse { throw InvalidLoanContractException(loan.loanId, "unknown method '${loan.method}'") }
        val scheduled = loan.remainingInstallments.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.principal) }
        if (scheduled.compareTo(loan.outstandingPrincipal) != 0) {
            throw InvalidLoanContractException(
                loan.loanId,
                "outstanding ${loan.outstandingPrincipal} != sum of remaining principal $scheduled",
            )
        }
        return Instrument(
            id = loan.loanId.toString(),
            kind = if (method == AmortizationMethod.BULLET) InstrumentKind.BULLET else InstrumentKind.AMORTISING_LOAN,
            glAccountCode = loan.glAccountCode,
            currency = loan.currency,
            outstanding = loan.outstandingPrincipal,
            valueDate = loan.disbursedOn,
            maturityDate = loan.maturityDate,
            rateTerms = rateTerms(loan),
            counterpartyRef = loan.counterpartyRef.toString(),
            ifrs9Stage = loan.ifrs9Stage,
            extension = LoanExtension(
                method = method,
                periodsPerYear = loan.periodsPerYear,
                remainingInstallments = loan.remainingInstallments.sortedBy { it.number },
            ),
        )
    }

    private fun rateTerms(loan: LoanContract): RateTerms = when (loan.rateType) {
        "FIXED" -> RateTerms(RateType.FIXED, loan.nominalAnnualRate)
        "FLOATING" -> {
            val index = loan.rateIndex?.let { name -> CurveIndex.entries.firstOrNull { it.name == name } }
                ?: throw InvalidLoanContractException(loan.loanId, "floating on unknown index '${loan.rateIndex}'")
            if (loan.spread == null || loan.resetFrequencyMonths == null || loan.nextResetDate == null) {
                throw InvalidLoanContractException(loan.loanId, "floating without spread and reset terms")
            }
            RateTerms(
                rateType = RateType.FLOATING,
                currentAnnualRate = loan.nominalAnnualRate,
                index = index,
                spread = loan.spread,
                resetFrequencyMonths = loan.resetFrequencyMonths,
                nextResetDate = loan.nextResetDate,
            )
        }
        else -> throw InvalidLoanContractException(loan.loanId, "unknown rate type '${loan.rateType}'")
    }
}
