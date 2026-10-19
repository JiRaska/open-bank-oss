// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import java.time.LocalDate

/**
 * Unadjusted period boundaries `[start, d1, ..., end]` for a frequency, stub placement and
 * end-of-month rule. The first element is always `start`, the last always `end`, and the list is
 * strictly increasing.
 */
object DateGrid {
    fun boundaries(
        start: LocalDate,
        end: LocalDate,
        frequency: Frequency,
        stub: StubType,
        endOfMonth: Boolean,
    ): List<LocalDate> {
        require(end.isAfter(start)) { "end $end must be after start $start" }
        if (frequency == Frequency.Zero) return listOf(start, end)
        return when (stub.direction) {
            RollDirection.FROM_START -> forward(start, end, frequency, stub.isLong, endOfMonth)
            RollDirection.FROM_END -> backward(start, end, frequency, stub.isLong, endOfMonth).asReversed()
        }
    }

    private fun forward(start: LocalDate, end: LocalDate, f: Frequency, long: Boolean, eom: Boolean): List<LocalDate> {
        val stepper = stepper(start, f, eom)
        val dates = mutableListOf(start)
        var k = 1
        var d = stepper(k)
        while (d.isBefore(end)) {
            ScheduleLimits.check(dates.size, start, end, f)
            dates += d
            d = stepper(++k)
        }
        // d >= end: on the grid when d == end, otherwise the last period [dates.last, end) is a stub.
        if (d != end && long && dates.size > 1) dates.removeAt(dates.lastIndex)
        dates += end
        return dates
    }

    /** Boundaries in DESCENDING order, from end back to start. */
    private fun backward(start: LocalDate, end: LocalDate, f: Frequency, long: Boolean, eom: Boolean): List<LocalDate> {
        val stepper = stepper(end, f, eom)
        val dates = mutableListOf(end)
        var k = -1
        var d = stepper(k)
        while (d.isAfter(start)) {
            ScheduleLimits.check(dates.size, start, end, f)
            dates += d
            d = stepper(--k)
        }
        if (d != start && long && dates.size > 1) dates.removeAt(dates.lastIndex)
        dates += start
        return dates
    }

    private fun stepper(anchor: LocalDate, f: Frequency, eom: Boolean): (Int) -> LocalDate {
        val applyEom = eom && f is Frequency.Months && anchor.dayOfMonth == anchor.lengthOfMonth()
        return { k ->
            val d = f.step(anchor, k)
            if (applyEom) d.withDayOfMonth(d.lengthOfMonth()) else d
        }
    }
}
