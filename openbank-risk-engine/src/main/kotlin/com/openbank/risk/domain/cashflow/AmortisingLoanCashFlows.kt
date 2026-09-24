// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** How a loan's rate is set (ADR-0314 D5), as the cash-flow engine needs it. */
sealed interface LoanRate {
    /** [nominalAnnualRate] as a fraction, e.g. `0.069`. */
    data class Fixed(val nominalAnnualRate: BigDecimal) : LoanRate

    /** Index + [spread] (fraction), projected per payment period from the index's curve. */
    data class Floating(val index: CurveIndex, val spread: BigDecimal) : LoanRate
}

/** The remaining life of one amortising loan as seen at the run's as-of date. */
data class AmortisingLoan(
    val currency: String,
    val outstandingPrincipal: BigDecimal,
    val rate: LoanRate,
    val periodsPerYear: Int,
    val remainingPeriods: Int,
    val method: AmortizationMethod,
    val nextDueDate: LocalDate,
)

/**
 * Contractual flows of an amortising loan (ADR-0313 D3, ADR-0314 D6). Every flow is an INFLOW to
 * the bank (positive): the loan is an asset.
 *
 * **FIXED** delegates to libs `Amortization.schedule` — the very schedule the loan is booked and
 * serviced against — so the risk engine cannot project a plan lending would not collect.
 *
 * **FLOATING** reproduces that algorithm period by period with a per-period rate: the index rate
 * for `[due − period, due]` (start clamped to the curve's as-of) is the curve's
 * [Curve.averageForwardRate], plus the spread, divided by `periodsPerYear` exactly as the fixed
 * schedule divides its nominal rate. The index resets every PAYMENT period in phase 0 — lending's
 * `resetFrequencyMonths` is not yet on the snapshot. For ANNUITY the payment is recomputed over the
 * remaining balance and periods only when the period rate changes; with an unchanged rate it is
 * the fixed schedule's payment, which is what makes a flat curve reproduce that schedule to the
 * cent (held to `AmortisingLoanCashFlowsTest`).
 *
 * The continuously-compounded average forward is used rather than the simple
 * [Curve.forwardRate] because only it is period-length independent on a flat curve: a simple
 * forward from a flat curve differs between a 28- and a 31-day month, so "flat curve = fixed rate"
 * would not hold. Phase-0 approximation, recorded here so a later switch is a visible change.
 *
 * NOT wired to the snapshot in this PR: the snapshot carries no loan contracts yet (that is the
 * instrument-model PR, ADR-0314 D4). This is the engine's loan leg, tested on its own.
 */
object AmortisingLoanCashFlows {

    private const val MONTHS_PER_YEAR = 12L
    private val VALID_PERIODS = (1..MONTHS_PER_YEAR.toInt()).filter { MONTHS_PER_YEAR.toInt() % it == 0 }.toSet()

    fun expand(loan: AmortisingLoan, curve: Curve? = null): List<CashFlow> {
        require(loan.outstandingPrincipal.signum() > 0) { "outstanding principal must be positive" }
        require(loan.remainingPeriods > 0) { "remaining term must be at least one period" }
        require(loan.periodsPerYear in VALID_PERIODS) { "periodsPerYear must divide 12: ${loan.periodsPerYear}" }
        return when (val rate = loan.rate) {
            is LoanRate.Fixed -> fixed(loan, rate)
            is LoanRate.Floating -> floating(loan, rate, requireNotNull(curve) { "a floating loan needs a curve" })
        }
    }

    private fun fixed(loan: AmortisingLoan, rate: LoanRate.Fixed): List<CashFlow> {
        val schedule = Amortization.schedule(
            principal = Money.of(loan.outstandingPrincipal, loan.currency),
            nominalAnnualRate = rate.nominalAnnualRate,
            termPeriods = loan.remainingPeriods,
            firstDueDate = loan.nextDueDate,
            periodsPerYear = loan.periodsPerYear,
            method = loan.method,
        )
        return schedule.installments.flatMap {
            flows(it.dueDate, loan.currency, it.principal.amount, it.interest.amount)
        }
    }

    private fun floating(loan: AmortisingLoan, rate: LoanRate.Floating, curve: Curve): List<CashFlow> {
        require(curve.index == rate.index) { "loan floats on ${rate.index}, curve is ${curve.index}" }
        require(loan.nextDueDate.isAfter(curve.asOf)) { "next due date must be after the curve's as-of" }
        val scale = minorUnits(loan.currency)
        val monthsPerPeriod = MONTHS_PER_YEAR / loan.periodsPerYear
        val n = loan.remainingPeriods
        val ppy = BigDecimal(loan.periodsPerYear)
        val flatPrincipal = loan.outstandingPrincipal.divide(
            BigDecimal(n),
            BigMath.MC,
        ).setScale(scale, RoundingMode.HALF_EVEN)

        var opening = loan.outstandingPrincipal
        var payment: BigDecimal? = null
        var lastRate: BigDecimal? = null
        val out = ArrayList<CashFlow>(2 * n)
        for (k in 1..n) {
            val due = loan.nextDueDate.plusMonths(monthsPerPeriod * (k - 1))
            val start = maxOf(due.minusMonths(monthsPerPeriod), curve.asOf)
            val annual = curve.averageForwardRate(start, due).add(rate.spread)
            require(annual.signum() >= 0) { "projected rate for $start..$due is negative: $annual" }
            val periodRate = annual.divide(ppy, BigMath.MC)
            val interest = opening.multiply(periodRate, BigMath.MC).setScale(scale, RoundingMode.HALF_EVEN)
            if (loan.method == AmortizationMethod.ANNUITY &&
                (lastRate == null || lastRate.compareTo(periodRate) != 0)
            ) {
                payment = annuity(opening, periodRate, n - k + 1, scale)
            }
            lastRate = periodRate
            val principal = when {
                k == n -> opening
                loan.method == AmortizationMethod.BULLET -> BigDecimal.ZERO.setScale(scale)
                loan.method == AmortizationMethod.EQUAL_PRINCIPAL -> flatPrincipal
                else -> requireNotNull(payment).subtract(interest)
            }
            out += flows(due, loan.currency, principal, interest)
            opening = opening.subtract(principal)
        }
        return out
    }

    /** The same annuity formula as libs `Amortization`, so an unchanged rate gives its payment. */
    private fun annuity(balance: BigDecimal, periodRate: BigDecimal, periods: Int, scale: Int): BigDecimal {
        val raw = if (periodRate.signum() == 0) {
            balance.divide(BigDecimal(periods), BigMath.MC)
        } else {
            val discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(periodRate).pow(periods, BigMath.MC), BigMath.MC)
            balance.multiply(periodRate, BigMath.MC).divide(BigDecimal.ONE.subtract(discount), BigMath.MC)
        }
        return raw.setScale(scale, RoundingMode.HALF_EVEN)
    }

    private fun flows(date: LocalDate, currency: String, principal: BigDecimal, interest: BigDecimal) = listOf(
        CashFlow(date, currency, CashFlowKind.PRINCIPAL, principal),
        CashFlow(date, currency, CashFlowKind.INTEREST, interest),
    )
}
