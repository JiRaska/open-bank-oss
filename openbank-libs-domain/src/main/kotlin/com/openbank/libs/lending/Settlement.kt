// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A binding early-repayment quote (ADR-0215 D2). Pure output of the schedule, the
 * repayment position and the pinned compliance pack — the same inputs always produce
 * the same number, which is what makes the most litigated figure in consumer credit
 * examiner-auditable. [compensationCapped] records whether the contractual
 * compensation had to be cut to the pack's legal cap (ADR-0212).
 */
data class SettlementQuote(
    val asOfDate: LocalDate,
    val validUntil: LocalDate,
    val outstandingPrincipal: Money,
    val accruedInterest: Money,
    val compensation: Money,
    val unappliedCredit: Money,
    val total: Money,
    val compensationCapped: Boolean,
)

/**
 * Pure settlement and withdrawal math (ADR-0215 D2/D4). Day-count is ACT/365 fixed;
 * money is rounded half-up to the currency's minor unit. No framework, no I/O — the
 * service supplies the schedule (from [Amortization]), the repayment position and the
 * pack's legal cap; the function never reads state.
 */
object Settlement {

    private const val DAYS_IN_YEAR = 365L

    /**
     * @param paidThroughInstallment number of the last fully paid installment (0 = none yet)
     * @param contractualCompensationRate the early-repayment compensation the contract asks
     *        for, as a fraction of the outstanding principal (e.g. 0.01)
     * @param legalCompensationCap the pinned pack's cap; null = no compensation permitted
     * @param unappliedCredit overpayments not yet applied to the balance (subtracted)
     */
    fun quote(
        schedule: RepaymentSchedule,
        paidThroughInstallment: Int,
        asOf: LocalDate,
        contractualCompensationRate: BigDecimal,
        legalCompensationCap: BigDecimal?,
        unappliedCredit: Money? = null,
        quoteValidityDays: Int = 30,
    ): SettlementQuote {
        require(paidThroughInstallment >= 0) { "paidThroughInstallment must be >= 0" }
        val first = schedule.installments.first()
        require(paidThroughInstallment < schedule.installments.size || !asOf.isAfter(first.dueDate)) {
            "loan is fully settled by its schedule"
        }

        val outstanding = if (paidThroughInstallment == 0) {
            first.openingBalance
        } else {
            schedule.balanceAfter(paidThroughInstallment)
        }
        val settledAt = if (paidThroughInstallment == 0) {
            first.dueDate.minusMonths(MONTHS_PER_PERIOD / schedule.periodsPerYear)
        } else {
            schedule.installments.first { it.number == paidThroughInstallment }.dueDate
        }

        val accrued = dayInterest(outstanding, schedule.nominalAnnualRate, settledAt, asOf)
        val compensation = compensation(outstanding, contractualCompensationRate, legalCompensationCap)
        val credit = unappliedCredit ?: Money.zero(outstanding.currency.code)
        val total = outstanding + accrued + compensation.amount - credit

        return SettlementQuote(
            asOfDate = asOf,
            validUntil = asOf.plusDays(quoteValidityDays.toLong()),
            outstandingPrincipal = outstanding,
            accruedInterest = accrued,
            compensation = compensation.amount,
            unappliedCredit = credit,
            total = total,
            compensationCapped = compensation.capped,
        )
    }

    /**
     * Statutory day-interest owed on a statutory withdrawal (ADR-0215 D4): the customer
     * returns the principal and pays interest only for the days the money was actually
     * drawn — the contract is voided, not enforced.
     */
    fun withdrawalInterest(
        principal: Money,
        nominalAnnualRate: BigDecimal,
        disbursedAt: LocalDate,
        withdrawnAt: LocalDate,
    ): Money = dayInterest(principal, nominalAnnualRate, disbursedAt, withdrawnAt)

    private const val MONTHS_PER_PERIOD = 12L

    private data class Compensation(val amount: Money, val capped: Boolean)

    private fun compensation(outstanding: Money, contractualRate: BigDecimal, legalCap: BigDecimal?): Compensation {
        if (legalCap == null || contractualRate <= BigDecimal.ZERO) {
            return Compensation(Money.zero(outstanding.currency.code), capped = false)
        }
        val applied = minOf(contractualRate, legalCap)
        return Compensation(scale(outstanding.amount * applied, outstanding), capped = applied < contractualRate)
    }

    private fun dayInterest(principal: Money, annualRate: BigDecimal, from: LocalDate, to: LocalDate): Money {
        val days = ChronoUnit.DAYS.between(from, to).coerceAtLeast(0)
        if (days == 0L || principal.isZero()) return Money.zero(principal.currency.code)
        val raw = principal.amount
            .multiply(annualRate)
            .multiply(BigDecimal(days))
            .divide(BigDecimal(DAYS_IN_YEAR), MC)
        return scale(raw, principal)
    }

    private val MC = java.math.MathContext.DECIMAL128

    private fun scale(amount: BigDecimal, like: Money): Money =
        Money.of(amount.setScale(like.currency.defaultFractionDigits, RoundingMode.HALF_UP), like.currency.code)
}
