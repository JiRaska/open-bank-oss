// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Remaining actual credit classified under the ordinary DE §899 allowance. The upstream
 * classification supplies the statutory allowance month, which can differ from the credit
 * month. Only classified funds are represented here. Moratoria and certificate decisions are
 * evaluated separately.
 */
data class ProtectedCreditCohort(
    val bookedSequence: Long,
    val creditedAt: Instant,
    val allowanceMonth: YearMonth,
    val remaining: Money,
) {
    init {
        require(remaining.currency.code == "EUR" && remaining.amount.signum() >= 0)
    }
}

object ProtectedCreditCohorts {
    private const val CARRY_MONTHS = 3L
    private val berlin = ZoneId.of("Europe/Berlin")

    fun available(cohorts: List<ProtectedCreditCohort>, at: Instant): Money {
        val amount = eligible(cohorts, at).fold(BigDecimal.ZERO) { total, cohort -> total + cohort.remaining.amount }
        return Money.of(amount, "EUR")
    }

    /**
     * Returns the resulting cohorts after consumption. A persistent caller must serialize this
     * calculation with its matching reservations and changes to the funded balance.
     */
    fun consume(cohorts: List<ProtectedCreditCohort>, at: Instant, amount: Money): List<ProtectedCreditCohort> {
        require(amount.currency.code == "EUR" && amount.amount.signum() > 0)
        val ordered = eligible(cohorts, at)
        require(ordered.fold(BigDecimal.ZERO) { total, cohort -> total + cohort.remaining.amount } >= amount.amount)
        var unallocated = amount.amount
        val changed = ordered.associate { cohort ->
            val taken = unallocated.min(cohort.remaining.amount)
            unallocated -= taken
            cohort.bookedSequence to cohort.copy(remaining = Money.of(cohort.remaining.amount - taken, "EUR"))
        }
        return cohorts.map { changed[it.bookedSequence] ?: it }
    }

    private fun eligible(cohorts: List<ProtectedCreditCohort>, at: Instant): List<ProtectedCreditCohort> {
        require(cohorts.distinctBy { it.bookedSequence }.size == cohorts.size)
        val month = YearMonth.from(at.atZone(berlin))
        return cohorts.filter {
            it.creditedAt <= at && month in it.allowanceMonth..it.allowanceMonth.plusMonths(CARRY_MONTHS)
        }.sortedWith(compareBy(ProtectedCreditCohort::creditedAt, ProtectedCreditCohort::bookedSequence))
    }
}
