// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-0269 rule 4 — price comes only from the server, and only as a quote.
 *
 * Two distinct things live here, and the distinction is the point:
 *
 *  - [CreditQuote] is INDICATIVE. It says what this shape of credit would cost on today's published
 *    terms. It binds nobody, it is not a decision, and it carries no application.
 *  - A binding offer is a separate object produced by the decision engine after an assessment. It
 *    is not modelled here precisely so that a quote can never be mistaken for one: an offer has an
 *    id, an expiry and an accept endpoint; a quote has none of those.
 *
 * Nothing else in the fleet may compute an instalment or an APRC. The client is forbidden by
 * ADR-0269 rule 4; campaign templates and models are forbidden for the same reason. A second
 * implementation disagreeing in the fourth decimal is a mis-disclosure, not a rounding difference.
 */
data class CreditQuoteRequest(
    val principal: Money,
    val termMonths: Int,
    val nominalAnnualRate: BigDecimal,
    val method: AmortizationMethod = AmortizationMethod.ANNUITY,
    /** Charges paid at drawdown (arrangement fee). Reduces what the customer actually receives. */
    val upfrontFee: Money? = null,
    /** Charges paid with each instalment (account/administration fee). */
    val monthlyFee: Money? = null,
) {
    init {
        require(termMonths > 0) { "termMonths must be positive" }
        require(principal.amount > BigDecimal.ZERO) { "principal must be positive" }
        require(nominalAnnualRate >= BigDecimal.ZERO) { "nominalAnnualRate must not be negative" }
        require(upfrontFee == null || upfrontFee.currency == principal.currency) {
            "upfrontFee must be in the principal's currency"
        }
        require(monthlyFee == null || monthlyFee.currency == principal.currency) {
            "monthlyFee must be in the principal's currency"
        }
        require(upfrontFee == null || upfrontFee.amount < principal.amount) {
            "upfrontFee must be smaller than the principal"
        }
    }
}

/**
 * The indicative price.
 *
 * [aprc] is nullable and null means "could not be computed", which callers must render as an
 * absent figure and never as zero — a 0% APRC reads to a customer as free credit. It is null for a
 * genuinely costless loan (no interest, no fees), which is also the honest rendering: there is no
 * rate of charge to disclose.
 */
data class CreditQuote(
    val principal: Money,
    val termMonths: Int,
    val nominalAnnualRate: BigDecimal,
    val monthlyPayment: Money,
    val totalPayable: Money,
    val totalCostOfCredit: Money,
    val aprc: BigDecimal?,
    val validUntil: Instant,
)

object CreditQuoteCalculator {

    /**
     * Price [request] as of [now], valid for [validityDuration].
     *
     * The instalment comes from the shared [Amortization] schedule rather than a formula rewritten
     * here, so a quote and the loan actually booked from it cannot disagree — that divergence is
     * the classic "the app said 6,100 and the contract says 6,142" complaint.
     *
     * The APRC is solved over the SAME cash flows the customer will really see: the net advance
     * (principal minus any upfront fee) against every instalment plus its monthly fee. Fees are
     * not a decoration on the rate; they are the reason the APRC exists.
     */
    fun quote(
        request: CreditQuoteRequest,
        now: Instant,
        validityDuration: java.time.Duration,
        firstDueDate: LocalDate,
    ): CreditQuote {
        val schedule = Amortization.schedule(
            principal = request.principal,
            nominalAnnualRate = request.nominalAnnualRate,
            termPeriods = request.termMonths,
            periodsPerYear = MONTHS_PER_YEAR,
            method = request.method,
            firstDueDate = firstDueDate,
        )
        val monthlyFee = request.monthlyFee ?: Money.of(BigDecimal.ZERO, request.principal.currency.code)
        val firstPayment = schedule.installments.first().payment + monthlyFee
        val totalFees = Money(monthlyFee.amount.multiply(BigDecimal(request.termMonths)), monthlyFee.currency)
        val totalPayable = schedule.totalPayment + totalFees + (request.upfrontFee ?: zero(request))
        val netAdvance = request.principal - (request.upfrontFee ?: zero(request))

        return CreditQuote(
            principal = request.principal,
            termMonths = request.termMonths,
            nominalAnnualRate = request.nominalAnnualRate,
            // The first instalment, not an average: an annuity's payments are equal, and for the
            // other methods the first is the one the customer must be able to afford.
            monthlyPayment = firstPayment,
            totalPayable = totalPayable,
            totalCostOfCredit = totalPayable - request.principal,
            aprc = Aprc.solve(
                advances = listOf(Aprc.CashFlow(BigDecimal.ZERO, netAdvance.amount)),
                payments = schedule.installments.map {
                    Aprc.CashFlow(
                        yearsFromStart = BigDecimal(it.number)
                            .divide(BigDecimal(MONTHS_PER_YEAR), java.math.MathContext.DECIMAL128),
                        amount = (it.payment + monthlyFee).amount,
                    )
                },
            ),
            validUntil = now.plus(validityDuration),
        )
    }

    private fun zero(request: CreditQuoteRequest) = Money.of(BigDecimal.ZERO, request.principal.currency.code)

    private const val MONTHS_PER_YEAR = 12
}
