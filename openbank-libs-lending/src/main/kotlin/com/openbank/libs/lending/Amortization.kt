// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
 *
 * Small-principal guard: when the amount repaid per period is only a few minor units, rounding the
 * level payment (or the flat principal) can over-repay the loan before the last period (negative
 * closing balances and a negative final payment) or under-repay it into a balloon. Such a schedule
 * is detected and rebuilt by re-deriving the level amount from the OUTSTANDING balance and the
 * REMAINING periods each period, with the principal portion clamped to the opening balance — so
 * drift self-corrects instead of compounding. Ordinary schedules, where the classic construction
 * already closes cleanly, are returned unchanged. A schedule whose regular amount is below one
 * minor unit of the currency cannot be expressed at all and is rejected.
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

        val unit = BigDecimal.ONE.movePointLeft(scale)
        if (method != AmortizationMethod.BULLET) {
            val regular = if (method == AmortizationMethod.ANNUITY) {
                annuityRaw(principal.amount, periodRate, termPeriods)
            } else {
                principal.amount.divide(BigDecimal(termPeriods), MC)
            }
            require(regular >= unit) {
                "Regular ${method.name.lowercase()} amount $regular ${principal.currency.code} is below one minor " +
                    "unit ($unit) for $principal over $termPeriods periods — the schedule cannot be expressed"
            }
        }

        val classic = classicSchedule(principal, periodRate, termPeriods, firstDueDate, monthsPerPeriod, method, scale)
        val installments = if (classic != null && isWellFormed(classic, method, unit)) {
            classic
        } else {
            reamortizedSchedule(principal, periodRate, termPeriods, firstDueDate, monthsPerPeriod, method, scale)
        }
        return RepaymentSchedule(method, nominalAnnualRate, periodsPerYear, installments)
    }

    /** The original fixed-amount construction; kept byte-identical for every ordinary schedule. */
    @Suppress("LongParameterList")
    private fun classicSchedule(
        principal: Money,
        periodRate: BigDecimal,
        termPeriods: Int,
        firstDueDate: LocalDate,
        monthsPerPeriod: Long,
        method: AmortizationMethod,
        scale: Int,
    ): List<Installment>? {
        val installments = ArrayList<Installment>(termPeriods)
        var opening = principal
        val fixedPayment = if (method == AmortizationMethod.ANNUITY) {
            money(annuityRaw(principal.amount, periodRate, termPeriods), principal, scale)
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

            // A rounded annuity can cover less than this period's rounded interest. Letting
            // negative principal compound may overflow Money before isWellFormed can reject
            // the completed schedule; reamortize as soon as that first line appears.
            if (principalDue.amount.signum() < 0) return null

            val closing = opening - principalDue
            val payment = principalDue + interest
            installments += Installment(n, dueDate, opening, principalDue, interest, payment, closing)
            opening = closing
        }
        return installments
    }

    /**
     * A classic schedule is accepted when no line is negative and, for an annuity, the final
     * payment stays within the rounding-drift bound (one minor unit per period) of the regular one.
     */
    private fun isWellFormed(installments: List<Installment>, method: AmortizationMethod, unit: BigDecimal): Boolean {
        val noNegative = installments.none {
            it.principal.amount.signum() < 0 || it.payment.amount.signum() < 0 || it.closingBalance.amount.signum() < 0
        }
        if (!noNegative || method != AmortizationMethod.ANNUITY) return noNegative
        val drift = (installments.last().payment.amount - installments.first().payment.amount).abs()
        return drift <= unit.multiply(BigDecimal(installments.size))
    }

    /**
     * Degenerate-case construction: each period's level amount is re-derived from the outstanding
     * balance over the remaining periods, the principal portion is clamped to `[0, opening]`, and
     * the final period clears the residual. Principal parts therefore sum to exactly [principal].
     */
    @Suppress("LongParameterList")
    private fun reamortizedSchedule(
        principal: Money,
        periodRate: BigDecimal,
        termPeriods: Int,
        firstDueDate: LocalDate,
        monthsPerPeriod: Long,
        method: AmortizationMethod,
        scale: Int,
    ): List<Installment> {
        val installments = ArrayList<Installment>(termPeriods)
        var opening = principal
        for (n in 1..termPeriods) {
            val remaining = termPeriods - n + 1
            val dueDate = firstDueDate.plusMonths(monthsPerPeriod * (n - 1))
            val interest = money(opening.amount.multiply(periodRate, MC), principal, scale)
            val rawPrincipal: BigDecimal = when {
                n == termPeriods -> opening.amount
                method == AmortizationMethod.EQUAL_PRINCIPAL ->
                    money(opening.amount.divide(BigDecimal(remaining), MC), principal, scale).amount
                else -> // ANNUITY (BULLET never reaches here: it has no negative line to repair)
                    money(annuityRaw(opening.amount, periodRate, remaining), principal, scale).amount - interest.amount
            }
            val principalDue = Money(rawPrincipal.max(BigDecimal.ZERO).min(opening.amount), principal.currency)
            val closing = opening - principalDue
            installments += Installment(n, dueDate, opening, principalDue, interest, principalDue + interest, closing)
            opening = closing
        }
        return installments
    }

    /**
     * Annuity payment A = P·i / (1 − (1+i)^−n), degenerating to P/n when the rate is zero.
     * Computed at DECIMAL128; callers round to the currency minor unit.
     */
    private fun annuityRaw(principal: BigDecimal, periodRate: BigDecimal, n: Int): BigDecimal =
        if (periodRate.signum() == 0) {
            principal.divide(BigDecimal(n), MC)
        } else {
            val onePlusI = BigDecimal.ONE.add(periodRate)
            val discount = BigDecimal.ONE.divide(onePlusI.pow(n, MC), MC) // (1+i)^-n
            principal.multiply(periodRate, MC).divide(BigDecimal.ONE.subtract(discount), MC)
        }

    private fun money(raw: BigDecimal, like: Money, scale: Int): Money =
        Money(raw.setScale(scale, RoundingMode.HALF_EVEN), like.currency)

    private fun zero(like: Money): Money =
        Money(BigDecimal.ZERO.setScale(like.currency.defaultFractionDigits), like.currency)
}
