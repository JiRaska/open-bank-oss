// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.calendar

import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.domain.calendar.BusinessDayConvention
import java.time.LocalDate

/**
 * How a date that is not a business day is moved onto one. [UNADJUSTED] leaves the date as it is
 * (ISDA "No Adjustment"); every other value names the kernel [BusinessDayConvention] it applies.
 */
enum class DateRoll(val kernel: BusinessDayConvention?) {
    UNADJUSTED(null),
    FOLLOWING(BusinessDayConvention.FOLLOWING),
    MODIFIED_FOLLOWING(BusinessDayConvention.MODIFIED_FOLLOWING),
    PRECEDING(BusinessDayConvention.PRECEDING),
    MODIFIED_PRECEDING(BusinessDayConvention.MODIFIED_PRECEDING),
}

/**
 * A business-day calendar as the schedule engine consumes it. Implementations are built from the
 * kernel [BusinessCalendar]s through [Calendars]; the engine never carries a holiday list of its
 * own. Implementations must be deterministic: [fingerprint] is hashed into a schedule's version.
 */
interface FinanceCalendar {
    /** Stable identifier, e.g. `CERTIS+TARGET2`. */
    val name: String

    fun isBusinessDay(date: LocalDate): Boolean

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

    /**
     * Moves [date] onto a business day under [roll]. A business day, and any date under
     * [DateRoll.UNADJUSTED], is returned unchanged. The modified conventions keep the date in its
     * calendar month (ISDA 4.12).
     */
    fun roll(date: LocalDate, roll: DateRoll): LocalDate {
        val convention = roll.kernel ?: return date
        if (isBusinessDay(date)) return date
        return when (convention) {
            BusinessDayConvention.FOLLOWING -> nextBusinessDay(date)
            BusinessDayConvention.PRECEDING -> previousBusinessDay(date)
            BusinessDayConvention.MODIFIED_FOLLOWING ->
                nextBusinessDay(date).let { if (it.month != date.month) previousBusinessDay(date) else it }
            BusinessDayConvention.MODIFIED_PRECEDING ->
                previousBusinessDay(date).let { if (it.month != date.month) nextBusinessDay(date) else it }
        }
    }

    /**
     * Moves [n] business days from [date]: forward for `n > 0`, backward for `n < 0`, unchanged
     * for `n == 0`. Every step lands on a business day, so the result is a business day whenever
     * `n != 0` even if [date] is not.
     */
    fun addBusinessDays(date: LocalDate, n: Int): LocalDate {
        var d = date
        repeat(Math.abs(n)) { d = if (n > 0) nextBusinessDay(d) else previousBusinessDay(d) }
        return d
    }

    /**
     * Canonical description of this calendar over `[from, to]`: its name and every weekday in the
     * range that is not a business day. Two calendars that agree on the range agree here.
     */
    fun fingerprint(from: LocalDate, to: LocalDate): String = "$name[${closedWeekdays(from, to)}]"

    /** Comma-joined weekdays in `[from, to]` that are not business days. */
    fun closedWeekdays(from: LocalDate, to: LocalDate): String = generateSequence(from) { it.plusDays(1) }
        .takeWhile { it <= to }
        .filter { it.dayOfWeek.value <= WEEKDAYS && !isBusinessDay(it) }
        .joinToString(",")
}

private const val WEEKDAYS = 5

/**
 * Factory for [FinanceCalendar]s over the kernel calendars.
 *
 * A **joint** calendar (the ISDA combination of several financial centres) treats a day as a
 * business day only when it is one in EVERY member — the rule for a flow that must settle in two
 * systems at once, e.g. one needing both CERTIS and TARGET2 open. An optional set of
 * bank-maintained extra closing days (an exceptional market closure, ADR-0344 D3) is applied on
 * top of the members.
 */
object Calendars {
    fun of(calendar: BusinessCalendar): FinanceCalendar = joint(listOf(calendar))

    fun joint(vararg members: BusinessCalendar): FinanceCalendar = joint(members.toList())

    fun joint(members: List<BusinessCalendar>, extraHolidays: Set<LocalDate> = emptySet()): FinanceCalendar {
        require(members.isNotEmpty()) { "a joint calendar needs at least one member" }
        return JointCalendar(members.distinctBy { it.name }, extraHolidays.toSortedSet())
    }

    /** The joint calendar of the kernel's [BusinessCalendar.forCurrency] for each ISO-4217 code. */
    fun forCurrencies(vararg currencyCodes: String): FinanceCalendar =
        joint(currencyCodes.map { BusinessCalendar.forCurrency(it) })

    private class JointCalendar(
        private val members: List<BusinessCalendar>,
        private val extraHolidays: Set<LocalDate>,
    ) : FinanceCalendar {
        override val name: String = members.joinToString("+") { it.name } +
            if (extraHolidays.isEmpty()) "" else "+EXTRA(${extraHolidays.joinToString(",")})"

        override fun isBusinessDay(date: LocalDate): Boolean =
            date !in extraHolidays && members.all { it.isBusinessDay(date) }

        private val memberNames = members.joinToString("+") { it.name }

        /** Members and effective closures only: an extra holiday outside the range changes nothing. */
        override fun fingerprint(from: LocalDate, to: LocalDate): String = "$memberNames[${closedWeekdays(from, to)}]"

        override fun toString(): String = "FinanceCalendar($name)"
    }
}
