// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * A bank business-day calendar: weekends plus a per-year set of public/bank holidays.
 *
 * Pure domain primitive (no framework dependencies). Holiday sets are computed per year on
 * first access and cached, so the business-day arithmetic stays cheap inside roll/add loops.
 *
 * Built-in calendars:
 * - [target2] — TARGET2 (EUR RTGS): closed only on New Year, Good Friday, Easter Monday,
 *   1 May, 25 + 26 December.
 * - [certis] — CERTIS (CZK clearing): the Czech public-holiday set.
 * - [weekendOnly] — Saturdays/Sundays only; fallback for currencies without a modelled calendar.
 */
class BusinessCalendar internal constructor(
    val name: String,
    private val weekend: Set<DayOfWeek>,
    private val holidayProvider: (Int) -> Set<LocalDate>,
) {
    private val holidayCache = ConcurrentHashMap<Int, Set<LocalDate>>()

    private fun holidays(year: Int): Set<LocalDate> = holidayCache.getOrPut(year) { holidayProvider(year) }

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek in weekend

    fun isHoliday(date: LocalDate): Boolean = date in holidays(date.year)

    fun isBusinessDay(date: LocalDate): Boolean = !isWeekend(date) && !isHoliday(date)

    /** The first business day strictly after [date]. */
    fun nextBusinessDay(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (!isBusinessDay(d)) d = d.plusDays(1)
        return d
    }

    /** The first business day strictly before [date]. */
    fun previousBusinessDay(date: LocalDate): LocalDate {
        var d = date.minusDays(1)
        while (!isBusinessDay(d)) d = d.minusDays(1)
        return d
    }

    /** Roll [date] onto a business day according to [convention] (no-op if already a business day). */
    fun roll(date: LocalDate, convention: BusinessDayConvention): LocalDate {
        if (isBusinessDay(date)) return date
        return when (convention) {
            BusinessDayConvention.FOLLOWING -> nextBusinessDay(date)
            BusinessDayConvention.PRECEDING -> previousBusinessDay(date)
            BusinessDayConvention.MODIFIED_FOLLOWING -> {
                val forward = nextBusinessDay(date)
                if (forward.monthValue != date.monthValue) previousBusinessDay(date) else forward
            }
            BusinessDayConvention.MODIFIED_PRECEDING -> {
                val backward = previousBusinessDay(date)
                if (backward.monthValue != date.monthValue) nextBusinessDay(date) else backward
            }
        }
    }

    /** Add [n] business days to [date] (n >= 0). Each step lands on a business day. */
    fun addBusinessDays(date: LocalDate, n: Int): LocalDate {
        require(n >= 0) { "Business-day offset must be non-negative: $n" }
        var d = date
        repeat(n) { d = nextBusinessDay(d) }
        return d
    }

    override fun toString(): String = "BusinessCalendar($name)"

    companion object {
        private val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        private val TARGET2 = BusinessCalendar("TARGET2", WEEKEND) { year ->
            setOf(
                LocalDate.of(year, 1, 1),
                Easter.goodFriday(year),
                Easter.monday(year),
                LocalDate.of(year, 5, 1),
                LocalDate.of(year, 12, 25),
                LocalDate.of(year, 12, 26),
            )
        }

        private val CERTIS = BusinessCalendar("CERTIS", WEEKEND) { year ->
            setOf(
                LocalDate.of(year, 1, 1), // New Year / Restoration of the Czech State
                Easter.goodFriday(year),
                Easter.monday(year),
                LocalDate.of(year, 5, 1), // Labour Day
                LocalDate.of(year, 5, 8), // Victory Day
                LocalDate.of(year, 7, 5), // Saints Cyril and Methodius
                LocalDate.of(year, 7, 6), // Jan Hus Day
                LocalDate.of(year, 9, 28), // Czech Statehood Day
                LocalDate.of(year, 10, 28), // Independent Czechoslovak State Day
                LocalDate.of(year, 11, 17), // Struggle for Freedom and Democracy Day
                LocalDate.of(year, 12, 24), // Christmas Eve
                LocalDate.of(year, 12, 25), // Christmas Day
                LocalDate.of(year, 12, 26), // St. Stephen's Day
            )
        }

        private val WEEKEND_ONLY = BusinessCalendar("WEEKEND-ONLY", WEEKEND) { emptySet() }

        fun target2(): BusinessCalendar = TARGET2

        fun certis(): BusinessCalendar = CERTIS

        fun weekendOnly(): BusinessCalendar = WEEKEND_ONLY

        /**
         * Calendar for the given ISO-4217 currency: EUR -> TARGET2, CZK -> CERTIS, everything
         * else -> weekend-only (honest fallback; national calendars for USD/GBP/... are not yet
         * modelled).
         */
        fun forCurrency(currencyCode: String): BusinessCalendar = when (currencyCode.uppercase()) {
            "EUR" -> TARGET2
            "CZK" -> CERTIS
            else -> WEEKEND_ONLY
        }
    }
}
