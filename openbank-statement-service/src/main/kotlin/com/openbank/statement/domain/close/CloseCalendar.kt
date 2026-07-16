// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.close

import java.time.LocalDate

/**
 * Pure month-window arithmetic for the self-healing catch-up close (ADR-0069 D3 / issue #470).
 *
 * The previous scheduler only ever closed the single prior month, so a skipped or failed run left a
 * permanent gap in the legal sequence. This computes the full set of month windows a pocket still
 * owes, so a later run heals earlier misses. Framework-free and exhaustively unit-tested.
 */
object CloseCalendar {

    /** [firstOfMonth, lastOfMonth] for the calendar month preceding [today]. */
    fun priorMonthBounds(today: LocalDate): Pair<LocalDate, LocalDate> {
        val lastOfPrior = today.withDayOfMonth(1).minusDays(1)
        return lastOfPrior.withDayOfMonth(1) to lastOfPrior
    }

    /**
     * The ordered (oldest-first) list of month windows to close for one pocket.
     *
     * - When [lastClosedPeriodTo] is non-null, returns every whole month strictly after it up to and
     *   including the month of [throughMonthEnd] — healing any missed months.
     * - When null (the pocket has never been closed), returns just the [throughMonthEnd] month: a
     *   brand-new pocket starts its legal sequence at the current cadence; pre-registration history
     *   is deliberately not back-invented.
     * - Capped at [maxLookbackMonths] windows as a safety bound, so a long-dormant pocket can never
     *   enqueue an unbounded backlog in a single run. The cap keeps the OLDEST owed months: the close
     *   only ever advances `latestClosedPeriodTo` forward, so keeping the newest would permanently
     *   drop the older months and break the legal sequence. Keeping the oldest also means the newest
     *   months simply wait for a later run — correct, since balance chaining needs each prior period
     *   closed first.
     *
     * Returns empty when the pocket is already current (last close == the through month).
     */
    fun monthsToClose(
        lastClosedPeriodTo: LocalDate?,
        throughMonthEnd: LocalDate,
        maxLookbackMonths: Int = 12,
    ): List<Pair<LocalDate, LocalDate>> {
        val throughStart = throughMonthEnd.withDayOfMonth(1)
        if (lastClosedPeriodTo == null) {
            return listOf(throughStart to monthEnd(throughStart))
        }
        // First month we owe = the month after the last closed month.
        var cursor = lastClosedPeriodTo.withDayOfMonth(1).plusMonths(1)
        val windows = ArrayList<Pair<LocalDate, LocalDate>>()
        while (!cursor.isAfter(throughStart)) {
            windows.add(cursor to monthEnd(cursor))
            cursor = cursor.plusMonths(1)
        }
        // Keep only the OLDEST [maxLookbackMonths] when a large gap exists — healing progresses
        // oldest-first so subsequent runs eventually reach the newest months (takeLast would strand
        // the dropped older months forever, since the close cursor only moves forward).
        return if (windows.size > maxLookbackMonths) windows.take(maxLookbackMonths) else windows
    }

    private fun monthEnd(firstOfMonth: LocalDate): LocalDate = firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth())
}
