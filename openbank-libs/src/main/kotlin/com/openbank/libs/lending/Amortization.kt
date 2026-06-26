// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Repayment-schedule generation for the lending bounded context (ADR-0028).
 *
 * Pure domain math — no persistence, no framework. The schedule is the contractual cash-flow plan
 * a loan is booked against; servicing posts each installment to the ledger and IFRS 9 provisioning
 * ([Ifrs9]) reads the outstanding balance off it. Three methods are supported:
 *
 *  - [AmortizationMethod.ANNUITY]          — constant total payment (French amortization); principal
 *                                            share grows, interest share shrinks over the term.
 *  - [AmortizationMethod.EQUAL_PRINCIPAL]  — constant principal (German amortization); total payment
 *                                            falls over the term as the interest base shrinks.
 *  - [AmortizationMethod.BULLET]           — interest-only until maturity, full principal at the end.
 *
 * Money is kept to the currency's minor-unit scale throughout; per-period rounding drift is absorbed
 * by the final installment so the schedule closes to exactly zero (no lost or phantom cents).
 */
enum class AmortizationMethod { ANNUITY, EQUAL_PRINCIPAL, BULLET }

/** One row of a [RepaymentSchedule]: the cash flow due on [dueDate]. */
data class Installment(
    val number: Int,
    val dueDate: LocalDate,
    val openingBalance: Money,
    val principal: Money,
    val interest: Money,
    val payment: Money,
    val closingBalance: Money,
)

/** The full contractual repayment plan for a loan. */
data class RepaymentSchedule(
    val method: AmortizationMethod,
    val nominalAnnualRate: BigDecimal,
    val periodsPerYear: Int,
    val installments: List<Installment>,
) {
    val totalPrincipal: Money = installments.map { it.principal }.reduce(Money::plus)
    val totalInterest: Money = installments.map { it.interest }.reduce(Money::plus)
    val totalPayment: Money = installments.map { it.payment }.reduce(Money::plus)

    /** Outstanding principal still owed immediately after installment [number] has been paid. */
    fun balanceAfter(number: Int): Money = installments.first { it.number == number }.closingBalance
}

object Amortization {

    private val MC = MathContext.DECIMAL128

    /**
     * Build a [RepaymentSchedule].
     *
     * @param principal          amount disbursed (the opening balance of installment 1).
     * @param nominalAnnualRate  nominal annual interest rate as a fraction, e.g. `0.069` for 6.9% p.a.
     * @param termPeriods        number of installments (> 0).
     * @param periodsPerYear     installments per year; must divide 12 evenly (12=monthly, 4=quarterly,
     *                           2=semi-annual, 1=annual). Drives both the periodic rate and the due-date step.
     * @param method             amortization method.
     * @param firstDueDate       due date of installment 1; subsequent installments step by 12/periodsPerYear months.
     */
    fun schedule(
        principal: Money,
        nominalAnnualRate: BigDecimal,
        termPeriods: Int,
        firstDueDate: LocalDate,
        periodsPerYear: Int = 12,
        method: AmortizationMethod = AmortizationMethod.ANNUITY,
    ): RepaymentSchedule {
        require(principal.isPositive()) { "Loan principal must be positive: $principal" }
        require(termPeriods > 0) { "Term must be at least one period: $termPeriods" }
        require(nominalAnnualRate.signum() >= 0) { "Nominal rate cannot be negative: $nominalAnnualRate" }
        require(periodsPerYear in intArrayOf(1, 2, 3, 4, 6, 12)) {
            "periodsPerYear must divide 12 evenly: $periodsPerYear"
        }

        val scale = principal.currency.defaultFractionDigits
        val monthsPerPeriod = 12L / periodsPerYear
        val periodRate = nominalAnnualRate.divide(BigDecimal(periodsPerYear), MC)

        val installments = ArrayList<Installment>(termPeriods)
        var opening = principal
        val fixedPayment = if (method == AmortizationMethod.ANNUITY) {
            annuityPayment(principal, periodRate, termPeriods, scale)
        } else {
            null
        }
        val flatPrincipal = if (method == AmortizationMethod.EQUAL_PRINCIPAL) {
            money(principal.amount.divide(BigDecimal(termPeriods), MC), principal, scale)
        } else {
            null
        }

        for (n in 1..termPeriods) {
            val last = n == termPeriods
            val dueDate = firstDueDate.plusMonths(monthsPerPeriod * (n - 1))
            val interest = money(opening.amount.multiply(periodRate, MC), principal, scale)

            val principalDue: Money = when {
                last -> opening // final installment clears the whole remaining balance, absorbing drift
                method == AmortizationMethod.BULLET -> zero(principal)
                method == AmortizationMethod.EQUAL_PRINCIPAL -> flatPrincipal!!
                else -> fixedPayment!! - interest // ANNUITY: principal is payment net of interest
            }

            val closing = opening - principalDue
            val payment = principalDue + interest
            installments += Installment(n, dueDate, opening, principalDue, interest, payment, closing)
            opening = closing
        }
        return RepaymentSchedule(method, nominalAnnualRate, periodsPerYear, installments)
    }

    /**
     * Annuity payment A = P·i / (1 − (1+i)^−n), degenerating to P/n when the rate is zero.
     * Computed at DECIMAL128 then rounded to the currency minor unit.
     */
    private fun annuityPayment(principal: Money, periodRate: BigDecimal, n: Int, scale: Int): Money {
        val raw = if (periodRate.signum() == 0) {
            principal.amount.divide(BigDecimal(n), MC)
        } else {
            val onePlusI = BigDecimal.ONE.add(periodRate)
            val discount = BigDecimal.ONE.divide(onePlusI.pow(n, MC), MC) // (1+i)^-n
            principal.amount.multiply(periodRate, MC).divide(BigDecimal.ONE.subtract(discount), MC)
        }
        return money(raw, principal, scale)
    }

    private fun money(raw: BigDecimal, like: Money, scale: Int): Money =
        Money(raw.setScale(scale, RoundingMode.HALF_EVEN), like.currency)

    private fun zero(like: Money): Money =
        Money(BigDecimal.ZERO.setScale(like.currency.defaultFractionDigits), like.currency)
}
