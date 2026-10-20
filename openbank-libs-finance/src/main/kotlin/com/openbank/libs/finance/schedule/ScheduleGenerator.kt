// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.RateSpec
import java.time.LocalDate

/**
 * Generates the schedule of a [LegSpec] — the one schedule engine of ADR-0344 D1.
 *
 * Algorithm, in order:
 * 1. Unadjusted accrual boundaries from [DateGrid] (anchor-based steps, stub, EOM rule).
 * 2. Accrual dates: each boundary rolled on the leg calendar when `adjustAccrualPeriod`, else kept.
 * 3. Payment boundaries from the payment frequency (same stub and EOM rule); each must also be an
 *    accrual boundary, and each accrual period is paid on the first payment boundary at or after
 *    its unadjusted end — so a 6M payment over 3M accruals pays two periods together.
 * 4. Payment date: the payment boundary, rolled when `adjustPaymentDate`, plus the payment lag in
 *    business days on the leg calendar.
 * 5. Fixing date (floating legs): the accrual start (or end, in arrears) moved back by the fixing
 *    lag in business days on the fixing calendar; with a zero lag, a non-business reference date
 *    rolls to the preceding business day.
 * 6. Notional and principal per period from [Amortiser].
 *
 * The output is deterministic: equal inputs give equal schedules.
 */
object ScheduleGenerator {
    fun generate(leg: LegSpec): Schedule {
        val spec = leg.schedule
        val rules = leg.dates
        val floating = leg.rate as? RateSpec.Floating
        if (floating != null) {
            require(floating.resetFrequency == spec.accrualFrequency) {
                "reset frequency ${floating.resetFrequency.token} must equal accrual frequency " +
                    "${spec.accrualFrequency.token}: compounding/averaging resets are not supported"
            }
        }
        val accrualGrid = DateGrid.boundaries(spec.start, spec.end, spec.accrualFrequency, spec.stub, spec.endOfMonth)
        val paymentGrid = DateGrid.boundaries(spec.start, spec.end, spec.paymentFrequency, spec.stub, spec.endOfMonth)
        val accrualSet = accrualGrid.toSet()
        require(paymentGrid.all { it in accrualSet }) {
            "payment boundaries $paymentGrid are not a subset of accrual boundaries $accrualGrid"
        }
        val (unadjustedGrid, adjusted) = adjust(accrualGrid, leg)
        val raw = unadjustedGrid.indices.drop(1).map { i ->
            val unadjusted = DateRange(unadjustedGrid[i - 1], unadjustedGrid[i])
            val accrual = DateRange(adjusted[i - 1], adjusted[i])
            RawPeriod(
                index = i - 1,
                unadjusted = unadjusted,
                accrual = accrual,
                paymentDate = paymentDate(leg, paymentGrid.first { !it.isBefore(unadjusted.end) }),
                fixingDate = floating?.let { fixingDate(it, accrual, rules) },
                yearFraction = leg.dayCount.yearFraction(accrual.start, accrual.end),
            )
        }
        return Schedule(leg, Amortiser.apply(leg, raw), rules.calendar.roll(spec.start, rules.roll))
    }

    /**
     * Rolls the accrual boundaries when the leg adjusts accrual. A period that rolling shrinks to
     * zero length (a one- or two-day stub over a weekend) is merged into its neighbour: its inner
     * boundary is dropped — the successor's start, or the predecessor's end at maturity. The start
     * and end are never dropped; a whole schedule that collapses is rejected.
     */
    private fun adjust(grid: List<LocalDate>, leg: LegSpec): Pair<List<LocalDate>, List<LocalDate>> {
        val rules = leg.dates
        if (!rules.adjustAccrualPeriod) return grid to grid
        val keptUnadjusted = mutableListOf(grid.first())
        val keptAdjusted = mutableListOf(rules.calendar.roll(grid.first(), rules.roll))
        for (i in 1 until grid.size) {
            val rolled = rules.calendar.roll(grid[i], rules.roll)
            val isEnd = i == grid.lastIndex
            if (rolled.isAfter(keptAdjusted.last())) {
                keptUnadjusted += grid[i]
                keptAdjusted += rolled
            } else if (isEnd) {
                require(keptUnadjusted.size > 1) { "rolling collapsed the whole schedule onto ${keptAdjusted.last()}" }
                keptUnadjusted[keptUnadjusted.lastIndex] = grid[i]
                keptAdjusted[keptAdjusted.lastIndex] = rolled
            }
        }
        require(keptAdjusted.size > 1 && keptAdjusted.last().isAfter(keptAdjusted.first())) {
            "rolling collapsed the whole schedule onto ${keptAdjusted.first()}"
        }
        return keptUnadjusted to keptAdjusted
    }

    private fun paymentDate(leg: LegSpec, boundary: LocalDate): LocalDate {
        val rules = leg.dates
        val rolled = if (rules.adjustPaymentDate) rules.calendar.roll(boundary, rules.roll) else boundary
        return rules.calendar.addBusinessDays(rolled, rules.paymentLagBusinessDays)
    }

    /**
     * The reset date is the period's start (end, in arrears) as a BUSINESS day of the leg: an
     * unadjusted accrual still resets on the rolled date (ISDA 2006 §6.1 Reset Date), so a period
     * starting on a Sunday month end fixes two days before the Friday it rolls back to, not before
     * the Sunday.
     */
    private fun fixingDate(rate: RateSpec.Floating, accrual: DateRange, rules: DateRules): LocalDate {
        val convention = rate.fixing
        val boundary = if (convention.inArrears) accrual.end else accrual.start
        val reference = rules.calendar.roll(boundary, rules.roll)
        return if (convention.lagBusinessDays == 0) {
            convention.calendar.roll(reference, BusinessDayConvention.PRECEDING)
        } else {
            convention.calendar.addBusinessDays(reference, -convention.lagBusinessDays)
        }
    }
}

/** A period before notional and principal are attached. */
internal data class RawPeriod(
    val index: Int,
    val unadjusted: DateRange,
    val accrual: DateRange,
    val paymentDate: LocalDate,
    val fixingDate: LocalDate?,
    val yearFraction: YearFraction,
)
